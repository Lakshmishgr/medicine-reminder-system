import java.util.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import static spark.Spark.*;

public class MedicineReminderSystem {

    // Twilio configuration loaded dynamically from system environment variables
    public static final String ACCOUNT_SID = System.getenv().getOrDefault("TWILIO_ACCOUNT_SID", "YOUR_ACCOUNT_SID");
    public static final String AUTH_TOKEN = System.getenv().getOrDefault("TWILIO_AUTH_TOKEN", "YOUR_AUTH_TOKEN");
    public static final String FROM_NUMBER = System.getenv().getOrDefault("TWILIO_FROM_NUMBER", "+18455529994");

    // Suppress verbose Spark/SLF4J internal logs
    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "off");
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "false");
        System.setProperty("org.slf4j.simpleLogger.showLogName", "false");
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "false");
        System.setProperty("org.slf4j.impl.StaticLoggerBinder", "false");
    }

    // Medicine reminder data model
    static class MedicineReminder {
        String medicineName;
        String time;
        String patientNumber;
        String caretakerNumber;
        String riskMessage;

        MedicineReminder(String medicineName, String time, String patientNumber,
                         String caretakerNumber, String riskMessage) {
            this.medicineName = medicineName;
            this.time = time;
            this.patientNumber = patientNumber;
            this.caretakerNumber = caretakerNumber;
            this.riskMessage = riskMessage;
        }
    }

    // Circular queue to manage scheduled medicine reminders
    static class ReminderQueue {
        private MedicineReminder[] arr;
        private int front, rear, size, capacity;
        private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");

        ReminderQueue() {
            capacity = 5;
            arr = new MedicineReminder[capacity];
            front = rear = -1;
            size = 0;
        }

        private boolean isFull() { return size == capacity; }
        boolean isEmpty() { return size == 0; }

        private void resize() {
            int newCap = capacity * 2;
            MedicineReminder[] newArr = new MedicineReminder[newCap];

            int i = 0;
            int idx = front;
            while (i < size) {
                newArr[i] = arr[idx];
                idx = (idx + 1) % capacity;
                i++;
            }

            arr = newArr;
            capacity = newCap;
            front = 0;
            rear = size - 1;
        }

        synchronized void addReminder(MedicineReminder r) {
            if (isFull()) resize();

            if (isEmpty()) {
                front = rear = 0;
            } else {
                rear = (rear + 1) % capacity;
            }

            arr[rear] = r;
            size++;
            System.out.println("Added: " + r.medicineName + " at " + r.time);
        }

        synchronized List<MedicineReminder> listAll() {
            List<MedicineReminder> out = new ArrayList<>();
            if (isEmpty()) return out;

            int idx = front;
            while (true) {
                out.add(arr[idx]);
                if (idx == rear) break;
                idx = (idx + 1) % capacity;
            }
            return out;
        }

        int findIndexByName(String name) {
            if (isEmpty()) return -1;
            int idx = front;
            while (true) {
                if (arr[idx].medicineName.equalsIgnoreCase(name)) return idx;
                if (idx == rear) break;
                idx = (idx + 1) % capacity;
            }
            return -1;
        }

        synchronized boolean removeByName(String name) {
            int idx = findIndexByName(name);
            if (idx == -1) return false;

            removeByIndex(idx);
            System.out.println("Removed: " + name);
            return true;
        }

        synchronized void snoozeAtIndex(int idx, int mins) {
            if (idx == -1 || isEmpty()) return;

            MedicineReminder r = arr[idx];
            try {
                LocalTime t = LocalTime.parse(r.time, formatter);
                r.time = t.plusMinutes(mins).format(formatter);
                System.out.println("Snoozed '" + r.medicineName + "' -> New time: " + r.time);
            } catch (Exception e) {
                System.out.println("Invalid time format: " + r.time);
            }
        }

        // Process queue and dispatch notifications for current time match
        synchronized void checkAndSend() {
            if (isEmpty()) return;

            LocalTime now = LocalTime.now();
            int idx = front;
            int count = size;

            while (count > 0 && !isEmpty()) {
                MedicineReminder r = arr[idx];
                boolean shouldRemove = false;

                try {
                    LocalTime t = LocalTime.parse(r.time, formatter);
                    if (now.getHour() == t.getHour() && now.getMinute() == t.getMinute()) {
                        sendNotification(r);
                        shouldRemove = true;
                    }
                } catch (Exception ignored) { }

                int nextIdx = (idx + 1) % capacity;

                if (shouldRemove) {
                    removeByIndex(idx);
                } else {
                    idx = nextIdx;
                }

                count--;
            }
        }

        private void removeByIndex(int idx) {
            int current = idx;
            while (current != rear) {
                int next = (current + 1) % capacity;
                arr[current] = arr[next];
                current = next;
            }

            arr[rear] = null;
            size--;
            rear = (rear - 1 + capacity) % capacity;

            if (size == 0) {
                front = rear = -1;
            }
        }

        private void sendNotification(MedicineReminder r) {
            String patientMsg = String.format(
                "💊 Medicine: %s\n⚠️ Risk: %s\n⏰ Time: %s\nPlease take it now.",
                r.medicineName, r.riskMessage, r.time
            );

            String caretakerMsg = String.format(
                "👩‍⚕️ Caregiver Alert\nPatient: %s\n💊 Medicine: %s\n⚠️ Risk: %s\n⏰ Time: %s",
                r.patientNumber, r.medicineName, r.riskMessage, r.time
            );

            sendSms(r.patientNumber, patientMsg);
            sendSms(r.caretakerNumber, caretakerMsg);

            System.out.println("✅ Notifications sent for: " + r.medicineName);
        }
    }

    static final ReminderQueue rq = new ReminderQueue();
    static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    static Thread reminderThread = null;
    static volatile boolean running = false;

    // Dispatches SMS using Twilio API (Formats 10-digit phone numbers automatically)
    public static void sendSms(String to, String msg) {
        if (to == null || to.isBlank()) return;

        String recipient = to.trim();
        if (!recipient.startsWith("+")) {
            recipient = "+91" + recipient;
        }

        try {
            Twilio.init(ACCOUNT_SID, AUTH_TOKEN);
            Message.creator(
                new PhoneNumber(recipient),
                new PhoneNumber(FROM_NUMBER),
                msg
            ).create();
            System.out.println("✅ SMS delivered to: " + recipient);
        } catch (Exception e) {
            System.out.println("❌ SMS delivery failed for " + recipient + " | Error: " + e.getMessage());
        }
    }

    static String json(Object o) { return gson.toJson(o); }

    // Background thread runner checking schedule every 10 seconds
    public static void startReminderThread() {
        if (running) return;
        running = true;
        reminderThread = new Thread(() -> {
            System.out.println("Reminder background process started.");
            while (running) {
                try {
                    rq.checkAndSend();
                    Thread.sleep(10000);
                } catch (InterruptedException ignored) { }
            }
        });
        reminderThread.setDaemon(true);
        reminderThread.start();
    }

    public static void stopReminderThread() {
        running = false;
        if (reminderThread != null) reminderThread.interrupt();
    }

    public static void main(String[] args) {
        port(4567);
        staticFiles.location("/public");

        // REST API routes
        get("/", (req, res) -> { res.redirect("/index.html"); return ""; });

        get("/api/reminders", (req, res) -> {
            res.type("application/json");
            return json(rq.listAll());
        });

        post("/api/reminders", (req, res) -> {
            res.type("application/json");
            try {
                MedicineReminder m = gson.fromJson(req.body(), MedicineReminder.class);
                if (m == null || m.medicineName == null) throw new Exception("Invalid payload");
                rq.addReminder(m);
                return json(Map.of("status", "ok"));
            } catch (Exception e) {
                res.status(400);
                return json(Map.of("status", "error", "message", e.getMessage()));
            }
        });

        delete("/api/reminders/:name", (req, res) -> {
            String name = req.params(":name");
            boolean ok = rq.removeByName(name);
            return json(Map.of("removed", ok));
        });

        post("/api/reminders/:name/snooze", (req, res) -> {
            String name = req.params(":name");
            Map payload = gson.fromJson(req.body(), Map.class);
            int mins = ((Number) payload.getOrDefault("minutes", 0)).intValue();
            int idx = rq.findIndexByName(name);
            rq.snoozeAtIndex(idx, mins);
            return json(Map.of("snoozed", true));
        });

        post("/api/start", (req, res) -> {
            startReminderThread();
            return json(Map.of("started", true));
        });

        startReminderThread();

        System.out.println("Web dashboard active at http://localhost:4567");
        try {
            java.awt.Desktop.getDesktop().browse(new java.net.URI("http://localhost:4567"));
        } catch (Exception e) {
            System.out.println("Could not open browser automatically: " + e.getMessage());
        }
    }
}