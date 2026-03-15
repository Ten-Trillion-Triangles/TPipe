class TraceDashboard {
    constructor() {
        this.traces = [];
        this.activeTraceId = null;
        this.ws = null;
        this.sessionToken = localStorage.getItem('tpipe_session') || '';
        this.baseUrl = window.location.origin;
        this.isConnected = false;
        this.searchQuery = '';

        this.elements = {
            authOverlay: document.getElementById('authOverlay'),
            authInput: document.getElementById('authKey'),
            searchInput: document.getElementById('searchInput'),
            traceList: document.getElementById('traceList'),
            traceCount: document.getElementById('traceCount'),
            traceFrame: document.getElementById('trace-frame'),
            contentHeader: document.getElementById('contentHeader'),
            emptyState: document.getElementById('emptyState'),
            liveIndicator: document.getElementById('liveIndicator'),
            statusText: document.getElementById('connectionStatusText')
        };

        if (this.elements.searchInput) {
            this.elements.searchInput.addEventListener('input', (e) => {
                this.searchQuery = e.target.value.toLowerCase();
                this.renderTraceList();
            });
        }

        if (this.sessionToken) {
            // Delay auth test to ensure DOM is ready
            setTimeout(() => this.fetchTraces(), 100);
        } else {
            this.elements.authOverlay.style.display = 'flex';
        }
    }

    login() {
        const key = this.elements.authInput.value;

        fetch(`${this.baseUrl}/api/auth/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ key: key })
        })
        .then(res => {
            if (!res.ok) throw new Error('Invalid credentials');
            return res.json();
        })
        .then(data => {
            this.sessionToken = data.token;
            localStorage.setItem('tpipe_session', this.sessionToken);
            this.elements.authOverlay.style.display = 'none';
            this.fetchTraces();
        })
        .catch(err => {
            console.error('Login failed:', err);
            alert('Login Failed: ' + err.message);
        });
    }

    fetchTraces() {
        fetch(`${this.baseUrl}/api/traces`, {
            headers: { 'Authorization': `Bearer ${this.sessionToken}` }
        })
        .then(res => {
            if (res.status === 401) {
                this.logout();
                throw new Error('Unauthorized');
            }
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
            console.error('Fetch traces failed:', err);
            if (err.message !== 'Unauthorized') {
                this.updateStatus('Disconnected', 'var(--accent-red)');
            }
        });
    }

    logout() {
        this.sessionToken = '';
        localStorage.removeItem('tpipe_session');
        this.elements.authOverlay.style.display = 'flex';
        this.updateStatus('Disconnected', 'var(--accent-red)');
        if (this.ws) {
            this.ws.close();
            this.ws = null;
        }
    }

    connectWebSocket() {
        if (this.ws) {
            this.ws.close();
        }

        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        this.ws = new WebSocket(`${protocol}//${window.location.host}/ws/traces?token=${this.sessionToken}`);

        this.ws.onopen = () => {
            this.isConnected = true;
            this.updateStatus('Live', 'var(--accent-green)');
        };

        this.ws.onmessage = (event) => {
            try {
                const newTrace = JSON.parse(event.data);
                this.traces.unshift(newTrace);
                this.renderTraceList();
            } catch (e) {
                console.error('Failed to parse WS message', e);
            }
        };

        this.ws.onclose = (event) => {
            this.isConnected = false;
            this.updateStatus('Disconnected', 'var(--accent-red)');

            if (event.code === 1008) { // Policy Violation (Unauthorized)
                 this.logout();
                 return;
            }

            // Attempt reconnect after 3s
            setTimeout(() => {
                if(this.sessionToken && this.elements.authOverlay.style.display === 'none') {
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
        const filteredTraces = this.traces.filter(trace => {
            if (!this.searchQuery) return true;
            return trace.name.toLowerCase().includes(this.searchQuery) ||
                   trace.id.toLowerCase().includes(this.searchQuery) ||
                   trace.status.toLowerCase().includes(this.searchQuery);
        });

        this.elements.traceCount.textContent = filteredTraces.length;

        if (filteredTraces.length === 0) {
            this.elements.traceList.innerHTML = '<div style="padding:20px; text-align:center; color:var(--text-muted); font-size: 0.85rem;">No traces found</div>';
            return;
        }

        let html = '';
        for(const trace of filteredTraces) {
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
            headers: { 'Authorization': `Bearer ${this.sessionToken}` }
        })
        .then(res => {
            if (res.status === 401) {
                this.logout();
                throw new Error('Unauthorized');
            }
            if (!res.ok) throw new Error('Trace not found');
            return res.json();
        })
        .then(trace => {
            this.elements.contentHeader.textContent = `Trace: ${trace.name} [${trace.pipelineId}]`;

            this.elements.traceFrame.style.display = 'block';

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
        });
    }
}

let app;
document.addEventListener('DOMContentLoaded', () => {
    app = new TraceDashboard();
});
