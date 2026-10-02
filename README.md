# Medicine Reminder System

A Java-based web application with SMS notification capabilities designed to remind patients and caregivers about medicine schedules in real time using Twilio.

## Features
* **Circular Queue Data Structure**: Efficiently manages scheduled medicine reminders.
* **Web UI**: Interactive dashboard powered by Spark Java framework.
* **SMS Alerts**: Integrates with Twilio API to dispatch alerts to patients and caregivers.
* **Snooze & Delete Options**: Manage active reminders directly from the UI.

## Prerequisites
* Java 17 or higher
* Apache Maven 3.x
* Twilio Account (Account SID, Auth Token, and Twilio Phone Number)

## How to Run

1. Clone the repository:
   ```bash
   git clone [https://github.com/YOUR_GITHUB_USERNAME/medicine-reminder-system.git](https://github.com/YOUR_GITHUB_USERNAME/medicine-reminder-system.git)
   cd medicine-reminder-system
   ```mermaid
graph TD
    A[Web UI / User Input] -->|HTTP POST| B[Spark Java Server]
    B -->|Enqueue Task| C[Circular Reminder Queue]
    C -->|Background Scheduler| D{Match Current Time?}
    D -- Yes --> E[Twilio API]
    E -->|SMS Alert| F[Patient]
    E -->|Caregiver Alert| G[Caretaker]
    D -- No --> C
