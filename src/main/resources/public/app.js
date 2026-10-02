async function api(path, method='GET', body=null) {
  const opts = { method, headers: {} };
  if (body) { opts.body = JSON.stringify(body); opts.headers['Content-Type']='application/json'; }
  const res = await fetch(path, opts);
  return res.json();
}

async function load() {
  const data = await api('/api/reminders');
  const tbody = document.querySelector('#tbl tbody');
  tbody.innerHTML = '';
  data.forEach((r, i) => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>${i+1}</td>
      <td>${r.medicineName}</td>
      <td>${r.time}</td>
      <td>${r.patientNumber}</td>
      <td>${r.caretakerNumber}</td>
      <td>${r.riskMessage}</td>
      <td>
        <button data-name="${r.medicineName}" class="snooze">Snooze</button>
        <button data-name="${r.medicineName}" class="remove">Remove</button>
      </td>
    `;
    tbody.appendChild(tr);
  });
  attachButtons();
}

function attachButtons(){
  document.querySelectorAll('.remove').forEach(b => {
    b.onclick = async ()=>{
      const name = b.dataset.name;
      await api('/api/reminders/'+encodeURIComponent(name), 'DELETE');
      load();
    };
  });
  document.querySelectorAll('.snooze').forEach(b => {
    b.onclick = async ()=>{
      const name = b.dataset.name;
      const mins = prompt('Minutes to snooze?', '5');
      if (!mins) return;
      await api('/api/reminders/'+encodeURIComponent(name)+'/snooze', 'POST', {minutes: parseInt(mins)});
      load();
    };
  });
}

document.getElementById('addBtn').onclick = async ()=>{
  const payload = {
    medicineName: document.getElementById('medicineName').value,
    time: document.getElementById('time').value,
    patientNumber: document.getElementById('patient').value,
    caretakerNumber: document.getElementById('caretaker').value,
    riskMessage: document.getElementById('risk').value
  };
  await api('/api/reminders', 'POST', payload);
  load();
};

document.getElementById('startBtn').onclick = async ()=>{
  await api('/api/start', 'POST');
  alert('Reminders started');
};

window.onload = load;