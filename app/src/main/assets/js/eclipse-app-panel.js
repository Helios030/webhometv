(function () {
    var panel = document.getElementById('connBody');
    if (!panel || document.getElementById('fm-app-actions') || !window.fongmi) return;
    var labels = window.__fmAppPanelLabels;
    var section = document.createElement('div');
    section.id = 'fm-app-actions';
    section.className = 'conn-actions';
    section.style.marginBottom = '16px';
    var status = document.createElement('div');
    status.setAttribute('role', 'status');
    status.style.cssText = 'color:var(--muted);font-size:12px;overflow-wrap:anywhere';
    function add(label, action) {
        var button = document.createElement('button');
        button.type = 'button';
        button.className = 'conn-btn tv-focusable';
        button.textContent = label;
        button.style.minHeight = '48px';
        button.onclick = function () {
            if (button.disabled) return;
            button.disabled = true;
            status.textContent = '';
            Promise.resolve().then(action).catch(function (error) {
                status.textContent = error.message || String(error);
            }).then(function () { button.disabled = false; });
        };
        section.appendChild(button);
    }
    add(labels.settings, function () { return window.fongmi.app.openSetting(); });
    add(labels.drive, function () { return window.fongmi.app.openDriveConfig(); });
    panel.insertBefore(status, panel.firstChild);
    panel.insertBefore(section, panel.firstChild);
})();
