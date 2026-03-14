class TraceDashboard {
    constructor() {
        this.traces = [];
        this.activeTraceId = null;
        this.ws = null;
        this.authKey = localStorage.getItem('tpipe_auth') || '';
        this.baseUrl = window.location.origin;
        this.isConnected = false;

        this.elements = {
            authOverlay: document.getElementById('authOverlay'),
            authInput: document.getElementById('authKey'),
            traceList: document.getElementById('traceList'),
            traceCount: document.getElementById('traceCount'),
            traceFrame: document.getElementById('trace-frame'),
            contentHeader: document.getElementById('contentHeader'),
            emptyState: document.getElementById('emptyState'),
            liveIndicator: document.getElementById('liveIndicator'),
            statusText: document.getElementById('connectionStatusText')
        };

        if (this.authKey) {
            this.elements.authInput.value = this.authKey;
            // Delay auth test to ensure DOM is ready
            setTimeout(() => this.authenticate(), 100);
        } else {
            this.elements.authOverlay.style.display = 'flex';
        }
    }

    authenticate() {
        this.authKey = this.elements.authInput.value;
        localStorage.setItem('tpipe_auth', this.authKey);

        fetch(`${this.baseUrl}/api/traces`, {
            headers: { 'Authorization': this.authKey }
        })
        .then(res => {
            if (res.status === 401) throw new Error('Unauthorized');
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            return res.json();
        })
        .then(data => {
            this.elements.authOverlay.style.display = 'none';
            this.traces = data;
            this.renderTraceList();
            this.connectWebSocket();
        })
        .catch(err => {
            console.error('Auth failed:', err);
            this.elements.authOverlay.style.display = 'flex';
            if(err.message === 'Unauthorized') {
                 alert('Invalid Authorization Key');
            } else {
                 console.warn('Failed to connect to API. Is server running?');
            }
            this.updateStatus('Disconnected', 'var(--accent-red)');
        });
    }

    connectWebSocket() {
        if (this.ws) {
            this.ws.close();
        }

        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        this.ws = new WebSocket(`${protocol}//${window.location.host}/ws/traces`);

        this.ws.onopen = () => {
            this.isConnected = true;
            this.updateStatus('Live', 'var(--accent-green)');
        };

        this.ws.onmessage = (event) => {
            try {
                const newTrace = JSON.parse(event.data);
                // Prepend to array
                this.traces.unshift(newTrace);
                this.renderTraceList();
            } catch (e) {
                console.error('Failed to parse WS message', e);
            }
        };

        this.ws.onclose = () => {
            this.isConnected = false;
            this.updateStatus('Disconnected', 'var(--accent-red)');
            // Attempt reconnect after 3s
            setTimeout(() => {
                if(this.authKey && this.elements.authOverlay.style.display === 'none') {
                    this.connectWebSocket();
                }
            }, 3000);
        };

        this.ws.onerror = (err) => {
            console.error('WebSocket Error', err);
        };
    }

    updateStatus(text, color) {
        this.elements.statusText.textContent = text;
        this.elements.liveIndicator.style.backgroundColor = color;
        this.elements.liveIndicator.style.boxShadow = `0 0 8px ${color}`;
    }

    formatTime(timestamp) {
        return new Date(timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    }

    renderTraceList() {
        this.elements.traceCount.textContent = this.traces.length;

        if (this.traces.length === 0) {
            this.elements.traceList.innerHTML = '<div style="padding:20px; text-align:center; color:var(--text-muted); font-size: 0.85rem;">No traces found</div>';
            return;
        }

        let html = '';
        for(const trace of this.traces) {
            const shortId = trace.id.substring(0, 8);
            let statusClass = 'status-PENDING';
            if (trace.status === 'SUCCESS') statusClass = 'status-SUCCESS';
            if (trace.status === 'FAILURE') statusClass = 'status-FAILURE';

            const isActive = this.activeTraceId === trace.id ? 'active' : '';

            html += `
                <div class="trace-item ${isActive}" onclick="app.loadTrace('${trace.id}')">
                    <div class="trace-header">
                        <span class="trace-name" title="${trace.name}">${trace.name}</span>
                        <span class="trace-status ${statusClass}">${trace.status}</span>
                    </div>
                    <div class="trace-meta">
                        <span class="trace-id">#${shortId}</span>
                        <span>${this.formatTime(trace.timestamp)}</span>
                    </div>
                </div>
            `;
        }
        this.elements.traceList.innerHTML = html;
    }

    loadTrace(id) {
        this.activeTraceId = id;
        this.renderTraceList(); // Update active highlight

        this.elements.emptyState.style.display = 'none';
        this.elements.contentHeader.textContent = `Loading trace ${id}...`;

        fetch(`${this.baseUrl}/api/traces/${id}`, {
            headers: { 'Authorization': this.authKey }
        })
        .then(res => {
            if (res.status === 401) throw new Error('Unauthorized');
            if (!res.ok) throw new Error('Trace not found');
            return res.json();
        })
        .then(trace => {
            this.elements.contentHeader.textContent = `Trace: ${trace.name} [${trace.pipelineId}]`;

            this.elements.traceFrame.style.display = 'block';

            // Using srcdoc if available, fallback to document.write
            if ('srcdoc' in this.elements.traceFrame) {
                this.elements.traceFrame.srcdoc = trace.htmlContent;
            } else {
                const frameDoc = this.elements.traceFrame.contentDocument || this.elements.traceFrame.contentWindow.document;
                frameDoc.open();
                frameDoc.write(trace.htmlContent);
                frameDoc.close();
            }
        })
        .catch(err => {
            console.error('Failed to load trace:', err);
            this.elements.contentHeader.textContent = `Error: ${err.message}`;
            this.elements.traceFrame.style.display = 'none';
            this.elements.emptyState.style.display = 'flex';
            this.elements.emptyState.innerHTML = `<div class="empty-icon">❌</div><div>${err.message}</div>`;

            if (err.message === 'Unauthorized') {
                this.elements.authOverlay.style.display = 'flex';
            }
        });
    }
}

let app;
document.addEventListener('DOMContentLoaded', () => {
    app = new TraceDashboard();
});
