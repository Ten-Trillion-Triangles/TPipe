/**
 * TPipe Trace Dashboard.
 *
 * Responsibilities:
 *   - Authenticate the dashboard user via the v2 `/api/auth/login` endpoint
 *     (and refresh via `/api/auth/refresh` when the access token expires).
 *   - Fetch the trace summary list, render it, and live-update it via the
 *     `/ws/traces` WebSocket channel.
 *   - Load the full trace payload on click and render it inside the sandboxed
 *     iframe.
 *   - Provide search, status filtering, manual refresh, and per-trace delete.
 *
 * v2 wire format reference (see TraceServer.kt for the authoritative shape):
 *   - GET /api/traces -> { items: TraceSummary[], total, limit, offset }
 *   - GET /api/traces/{id} -> TracePayload { pipelineId, htmlContent, name, status, tags }
 *   - DELETE /api/traces/{id} -> 204 No Content on success, 404 otherwise.
 */
class TraceDashboard {
    constructor() {
        this.traces = [];
        this.totalTraces = 0;
        this.unfilteredTotalTraces = 0; // size of the current fetch (server `total`)
        this.unfilteredTotalTraces = 0; // size of the most recent unfiltered fetch
        this.activeTraceId = null;
        this.activeTraceName = null;
        this.ws = null;
        this.sessionToken = localStorage.getItem('tpipe_session') || '';
        this.refreshToken = localStorage.getItem('tpipe_refresh') || '';
        this.baseUrl = window.location.origin;
        this.isConnected = false;
        this.searchQuery = '';
        this.statusFilter = '';
        this.kindFilter = '';
        this.authMode = 'KEY';
        this.activeAuthTab = 'key';
        this.reconnectAttempts = 0;
        this.reconnectTimer = null;
        this.isFetching = false;
        this.isLoggingIn = false;

        this.elements = {
            authOverlay: document.getElementById('authOverlay'),
            authInput: document.getElementById('authKey'),
            authUsername: document.getElementById('authUsername'),
            authPassword: document.getElementById('authPassword'),
            authError: document.getElementById('authError'),
            authHint: document.getElementById('authHint'),
            authSubmitBtn: document.getElementById('authSubmitBtn'),
            searchInput: document.getElementById('searchInput'),
            searchRow: document.getElementById('searchRow'),
            searchClear: document.querySelector('.search-clear'),
            traceList: document.getElementById('traceList'),
            traceCount: document.getElementById('traceCount'),
            traceFrame: document.getElementById('trace-frame'),
            contentHeader: document.getElementById('contentHeader'),
            emptyState: document.getElementById('emptyState'),
            statusContainer: document.getElementById('statusContainer'),
            statusText: document.getElementById('connectionStatusText'),
            logoutBtn: document.getElementById('logoutBtn'),
            refreshBtn: document.getElementById('refreshBtn'),
            deleteTraceBtn: document.getElementById('deleteTraceBtn'),
            openRawBtn: document.getElementById('openRawBtn'),
            filterChips: document.getElementById('filterChips'),
            kindFilterChips: document.getElementById('kindFilterChips')
        };

        this._wireEvents();
        this._applyFilterChipState();

        if (this.sessionToken) {
            // Defer so the DOM is fully ready before we paint anything.
            setTimeout(() => {
                this.fetchAuthConfig();
                this.fetchTraces();
            }, 50);
        } else {
            this.showAuthOverlay();
            this.fetchAuthConfig();
        }
    }

    _wireEvents() {
        const search = this.elements.searchInput;
        if (search) {
            search.addEventListener('input', (e) => {
                this.searchQuery = e.target.value.trim().toLowerCase();
                this._updateSearchClearVisibility();
                this.renderTraceList();
            });
        }

        // Enter inside any auth input submits the form.
        for (const id of ['authKey', 'authUsername', 'authPassword']) {
            const el = document.getElementById(id);
            if (el) {
                el.addEventListener('keydown', (e) => {
                    if (e.key === 'Enter') {
                        e.preventDefault();
                        this.login();
                    }
                });
            }
        }
    }

    // ----------------------- Auth UI helpers -----------------------

    showAuthOverlay() {
        this.elements.authOverlay.hidden = false;
        // Reset the inline style we used to set in v1; the new code relies on
        // the `hidden` attribute so the CSS `.auth-overlay[hidden]` rule
        // takes effect.
        this.elements.authOverlay.style.display = '';
        // Focus the first visible input so the user can type immediately.
        setTimeout(() => {
            const visibleInput = this.activeAuthTab === 'key'
                ? this.elements.authInput
                : this.elements.authUsername;
            if (visibleInput) visibleInput.focus();
        }, 30);
    }

    hideAuthOverlay() {
        this.elements.authOverlay.hidden = true;
        this.elements.authOverlay.style.display = 'none';
        this.elements.logoutBtn.hidden = false;
        this.clearAuthError();
    }

    showAuthError(message) {
        this.elements.authError.textContent = message;
        this.elements.authError.hidden = false;
    }

    clearAuthError() {
        this.elements.authError.textContent = '';
        this.elements.authError.hidden = true;
    }

    setAuthBusy(busy) {
        this.isLoggingIn = busy;
        this.elements.authSubmitBtn.disabled = busy;
        this.elements.authSubmitBtn.textContent = busy ? 'Connecting...' : 'Connect';
    }

    // ----------------------- Network: auth -----------------------

    async fetchAuthConfig() {
        try {
            const res = await fetch(`${this.baseUrl}/api/auth/config`);
            if (!res.ok) return;
            const data = await res.json();
            this.authMode = data.mode || 'KEY';
            this.configureAuthUI();
        } catch (err) {
            console.error('Failed to fetch auth config:', err);
        }
    }

    configureAuthUI() {
        const tabsContainer = document.getElementById('authTabsContainer');
        if (this.authMode === 'BOTH') {
            tabsContainer.style.display = 'flex';
            this.switchAuthTab('key');
        } else if (this.authMode === 'CREDENTIALS') {
            tabsContainer.style.display = 'none';
            this.switchAuthTab('credentials');
        } else {
            tabsContainer.style.display = 'none';
            this.switchAuthTab('key');
        }
        this._updateAuthHint();
    }

    _updateAuthHint() {
        const hint = this.elements.authHint;
        if (!hint) return;
        if (this.authMode === 'CREDENTIALS') {
            hint.textContent = 'Sign in with your TPipe account credentials.';
            hint.hidden = false;
        } else {
            hint.hidden = true;
        }
    }

    switchAuthTab(tab) {
        this.activeAuthTab = tab;
        const keyView = document.getElementById('authKeyView');
        const credentialsView = document.getElementById('authCredentialsView');
        const tabKey = document.getElementById('tabKey');
        const tabCredentials = document.getElementById('tabCredentials');

        if (tab === 'key') {
            keyView.classList.add('active');
            credentialsView.classList.remove('active');
            if (tabKey) tabKey.classList.add('active');
            if (tabCredentials) tabCredentials.classList.remove('active');
        } else {
            credentialsView.classList.add('active');
            keyView.classList.remove('active');
            if (tabCredentials) tabCredentials.classList.add('active');
            if (tabKey) tabKey.classList.remove('active');
        }
    }

    handleFileUpload(event) {
        const file = event.target.files[0];
        if (!file) return;
        const reader = new FileReader();
        reader.onload = (e) => {
            const contents = (e.target.result || '').trim();
            this.elements.authInput.value = contents;
            this.elements.authInput.focus();
        };
        reader.readAsText(file);
    }

    async login() {
        if (this.isLoggingIn) return;
        this.clearAuthError();

        let payload;
        if (this.activeAuthTab === 'key') {
            const key = this.elements.authInput.value.trim();
            if (!key) {
                this.showAuthError('Please enter an authorization key.');
                return;
            }
            payload = { key };
        } else {
            const username = this.elements.authUsername.value.trim();
            const password = this.elements.authPassword.value;
            if (!username || !password) {
                this.showAuthError('Please enter both username and password.');
                return;
            }
            payload = { username, password };
        }

        this.setAuthBusy(true);
        try {
            const res = await fetch(`${this.baseUrl}/api/auth/login`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            if (!res.ok) {
                let message = 'Invalid credentials';
                try {
                    const body = await res.json();
                    if (body && body.message) message = body.message;
                } catch (_) { /* fall back to default */ }
                throw new Error(message);
            }
            const data = await res.json();
            this.sessionToken = data.token;
            localStorage.setItem('tpipe_session', this.sessionToken);
            if (data.refreshToken) {
                this.refreshToken = data.refreshToken;
                localStorage.setItem('tpipe_refresh', this.refreshToken);
            } else {
                this.refreshToken = '';
                localStorage.removeItem('tpipe_refresh');
            }
            this.hideAuthOverlay();
            await this.fetchTraces();
        } catch (err) {
            console.error('Login failed:', err);
            this.showAuthError(err.message || 'Login failed.');
        } finally {
            this.setAuthBusy(false);
        }
    }

    logout() {
        this.sessionToken = '';
        this.refreshToken = '';
        localStorage.removeItem('tpipe_session');
        localStorage.removeItem('tpipe_refresh');
        this.traces = [];
        this.totalTraces = 0; // size of the current fetch (server `total`)
        this.unfilteredTotalTraces = 0; // size of the most recent unfiltered fetch
        this.activeTraceId = null;
        this.activeTraceName = null;
        this.reconnectAttempts = 0;
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
        if (this.ws) {
            try { this.ws.close(); } catch (_) {}
            this.ws = null;
        }
        this.elements.logoutBtn.hidden = true;
        this.elements.deleteTraceBtn.hidden = true;
        this.elements.openRawBtn.hidden = true;
        this.elements.traceFrame.style.display = 'none';
        this.elements.contentHeader.textContent = 'Select a trace to view details';
        this._renderActiveTags(null);
        this.renderTraceList();
        this._renderEmptyState({
            icon: '📊',
            message: 'Select a trace from the sidebar to view details'
        });
        this.setStatus('Disconnected', 'disconnected');
        this.showAuthOverlay();
    }

    // ----------------------- Network: traces -----------------------

    _buildTracesUrl() {
        const params = new URLSearchParams();
        params.set('limit', '200');
        if (this.statusFilter) params.set('status', this.statusFilter);
        if (this.searchQuery) params.set('q', this.searchQuery);
        return `${this.baseUrl}/api/traces?${params.toString()}`;
    }

    async fetchTraces() {
        if (this.isFetching) return;
        this.isFetching = true;
        this._setRefreshBusy(true);
        try {
            const res = await fetch(this._buildTracesUrl(), {
                headers: { 'Authorization': `Bearer ${this.sessionToken}` }
            });
            if (res.status === 401) {
                this.logout();
                return;
            }
            if (!res.ok) {
                throw new Error(`Failed to load traces (HTTP ${res.status})`);
            }
            const data = await res.json();
            // BUG FIX: the v2 endpoint returns `{items, total, limit, offset}`,
            // not a bare array. v1 code assigned `data` directly to `this.traces`
            // which caused `.filter` to throw and broke the entire UI on first
            // load (no traces rendered, WebSocket never connected).
            this.traces = Array.isArray(data.items) ? data.items : [];
            this.totalTraces = typeof data.total === 'number' ? data.total : this.traces.length;
            if (!this.statusFilter && !this.searchQuery) {
                this.unfilteredTotalTraces = this.totalTraces;
            }
            this.renderTraceList();
            this._connectWebSocketWithBackoff();
            // If the active trace was deleted on the server, clear the panel.
            if (this.activeTraceId && !this.traces.some(t => t.id === this.activeTraceId)) {
                this._clearActiveTrace();
            }
        } catch (err) {
            console.error('Fetch traces failed:', err);
            this.setStatus('Disconnected', 'disconnected');
            this._renderEmptyState({
                icon: '⚠️',
                message: err.message || 'Could not load traces',
                error: true,
                action: { label: 'Retry', onClick: () => this.fetchTraces() }
            });
        } finally {
            this.isFetching = false;
            this._setRefreshBusy(false);
        }
    }

    refresh() {
        return this.fetchTraces();
    }

    async deleteTraceById(id) {
        if (!id) return;
        const trace = this.traces.find(t => t.id === id);
        const label = trace ? trace.name : id;
        if (!confirm(`Delete trace "${label}"? This cannot be undone.`)) return;
        try {
            const res = await fetch(`${this.baseUrl}/api/traces/${encodeURIComponent(id)}`, {
                method: 'DELETE',
                headers: { 'Authorization': `Bearer ${this.sessionToken}` }
            });
            if (res.status === 401) {
                this.logout();
                return;
            }
            if (res.status === 404) {
                // Already gone on the server; treat as success locally.
            } else if (!res.ok) {
                throw new Error(`Delete failed (HTTP ${res.status})`);
            }
            this.traces = this.traces.filter(t => t.id !== id);
            this.totalTraces = Math.max(0, this.totalTraces - 1);
            this.unfilteredTotalTraces = Math.max(0, this.unfilteredTotalTraces - 1);
            if (this.activeTraceId === id) {
                this._clearActiveTrace();
            }
            this.renderTraceList();
        } catch (err) {
            console.error('Delete failed:', err);
            // Surface the error in the content area so the user keeps
            // their context (the auth overlay is gone, no need for an
            // alert() that blocks the dashboard).
            this.elements.contentHeader.textContent = `Error deleting trace`;
            this._renderEmptyState({
                icon: '⚠️',
                message: err.message || 'Could not delete trace',
                error: true
            });
        }
    }

    deleteActiveTrace() {
        if (this.activeTraceId) {
            this.deleteTraceById(this.activeTraceId);
        }
    }

    openTraceInNewTab() {
        if (!this.activeTraceId) return;
        const url = `${this.baseUrl}/api/traces/${encodeURIComponent(this.activeTraceId)}`;
        // Open the JSON payload directly in a new tab; useful for inspecting
        // raw trace data or saving it to disk.
        window.open(url, '_blank', 'noopener');
    }

    // ----------------------- Network: WebSocket -----------------------

    _connectWebSocketWithBackoff() {
        if (!this.sessionToken) return;
        if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
            return;
        }
        this.connectWebSocket();
    }

    connectWebSocket() {
        if (!this.sessionToken) return;
        if (this.ws) {
            try { this.ws.close(); } catch (_) {}
            this.ws = null;
        }
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        this.setStatus('Reconnecting...', 'reconnecting');
        let ws;
        try {
            ws = new WebSocket(`${protocol}//${window.location.host}/ws/traces?token=${encodeURIComponent(this.sessionToken)}`);
        } catch (err) {
            console.error('WebSocket construction failed:', err);
            this._scheduleReconnect();
            return;
        }
        this.ws = ws;

        ws.onopen = () => {
            this.isConnected = true;
            this.reconnectAttempts = 0;
            this.setStatus('Live', 'live');
        };

        ws.onmessage = (event) => {
            try {
                const payload = JSON.parse(event.data);
                this._handleWsMessage(payload);
            } catch (e) {
                console.error('Failed to parse WS message', e);
            }
        };

        ws.onclose = (event) => {
            this.isConnected = false;
            this.ws = null;
            if (event.code === 1008) {
                // Policy violation = auth failure; bounce the user back to the
                // login screen.
                this.logout();
                return;
            }
            this.setStatus('Disconnected', 'disconnected');
            this._scheduleReconnect();
        };

        ws.onerror = (err) => {
            console.error('WebSocket error', err);
        };
    }

    _scheduleReconnect() {
        if (!this.sessionToken) return;
        if (this.elements.authOverlay && !this.elements.authOverlay.hidden) return;
        if (this.reconnectTimer) return;
        this.reconnectAttempts += 1;
        // Exponential backoff with jitter, capped at 30s.
        const base = Math.min(30_000, 1_000 * Math.pow(2, this.reconnectAttempts - 1));
        const delay = base + Math.floor(Math.random() * 500);
        this.setStatus('Reconnecting...', 'reconnecting');
        this.reconnectTimer = setTimeout(() => {
            this.reconnectTimer = null;
            this.connectWebSocket();
        }, delay);
    }

    _handleWsMessage(payload) {
        if (!payload || typeof payload !== 'object') return;
        // v1 wire: bare TraceSummary object.
        // v2 wire: WebSocketEnvelope with `op` discriminator.
        if (payload.op) {
            if (payload.op === 'summary' && payload.id) {
                this._upsertSummary(payload);
            }
            // Other ops (event, ack, error) are for future live streaming
            // consumers and are ignored by the dashboard for now.
            return;
        }
        if (payload.id && payload.status) {
            this._upsertSummary(payload);
        }
    }

    _upsertSummary(summary) {
        const idx = this.traces.findIndex(t => t.id === summary.id);
        if (idx === -1) {
            this.traces.unshift(summary);
            this.totalTraces += 1;
            this.unfilteredTotalTraces += 1;
        } else {
            this.traces[idx] = { ...this.traces[idx], ...summary };
        }
        this.renderTraceList();
    }

    // ----------------------- UI state -----------------------

    setStatus(text, kind) {
        this.elements.statusText.textContent = text;
        const cls = this.elements.statusContainer.classList;
        cls.remove('is-live', 'is-disconnected', 'is-reconnecting');
        if (kind === 'live') cls.add('is-live');
        else if (kind === 'disconnected') cls.add('is-disconnected');
        else cls.add('is-reconnecting');
    }

    _setRefreshBusy(busy) {
        const btn = this.elements.refreshBtn;
        if (!btn) return;
        btn.disabled = busy;
        btn.classList.toggle('is-busy', busy);
    }

    _renderEmptyState({ icon, message, error = false, action = null }) {
        const el = this.elements.emptyState;
        el.innerHTML = '';
        const iconEl = document.createElement('div');
        iconEl.className = 'empty-icon';
        iconEl.textContent = icon || '📊';
        const msgEl = document.createElement('div');
        if (error) msgEl.classList.add('error-text');
        msgEl.textContent = message || '';
        el.appendChild(iconEl);
        el.appendChild(msgEl);
        if (action) {
            const wrap = document.createElement('div');
            wrap.className = 'empty-actions';
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'icon-btn';
            btn.textContent = action.label;
            btn.addEventListener('click', action.onClick);
            wrap.appendChild(btn);
            el.appendChild(wrap);
        }
        el.style.display = 'flex';
    }

    _clearActiveTrace() {
        this.activeTraceId = null;
        this.activeTraceName = null;
        this.elements.contentHeader.textContent = 'Select a trace to view details';
        this.elements.traceFrame.style.display = 'none';
        this.elements.deleteTraceBtn.hidden = true;
        this.elements.openRawBtn.hidden = true;
        this._renderActiveTags(null);
        this._renderEmptyState({
            icon: '📊',
            message: 'Select a trace from the sidebar to view details'
        });
    }

    _updateSearchClearVisibility() {
        const hasValue = this.searchQuery.length > 0;
        this.elements.searchRow.classList.toggle('has-value', hasValue);
    }

    clearSearch() {
        this.elements.searchInput.value = '';
        this.searchQuery = '';
        this._updateSearchClearVisibility();
        this.renderTraceList();
    }

    setStatusFilter(status) {
        this.statusFilter = status || '';
        this._applyFilterChipState();
        this.fetchTraces();
    }

    setKindFilter(kind) {
        this.kindFilter = kind || '';
        this._applyKindChipState();
        this.renderTraceList();
    }

    _applyKindChipState() {
        if (!this.elements.kindFilterChips) return;
        const chips = this.elements.kindFilterChips.querySelectorAll('.kind-chip');
        chips.forEach(chip => {
            const isActive = (chip.dataset.kind || '') === this.kindFilter;
            chip.classList.toggle('active', isActive);
        });
    }

    _applyFilterChipState() {
        const chips = this.elements.filterChips.querySelectorAll('.chip');
        chips.forEach(chip => {
            const isActive = (chip.dataset.status || '') === this.statusFilter;
            chip.classList.toggle('active', isActive);
        });
    }

    // ----------------------- Render -----------------------

    formatTime(timestamp) {
        if (!timestamp) return '';
        const date = new Date(timestamp);
        if (isNaN(date.getTime())) return '';
        const now = new Date();
        const deltaSec = Math.max(0, Math.floor((now - date) / 1000));
        if (deltaSec < 5) return 'just now';
        if (deltaSec < 60) return deltaSec + 's ago';
        const deltaMin = Math.floor(deltaSec / 60);
        if (deltaMin < 60) return deltaMin + 'm ago';
        const sameDay = date.toDateString() === now.toDateString();
        if (sameDay) {
            return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        }
        const yesterday = new Date(now);
        yesterday.setDate(now.getDate() - 1);
        if (date.toDateString() === yesterday.toDateString()) {
            return 'Yesterday ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        }
        const ageDays = (now - date) / (1000 * 60 * 60 * 24);
        if (ageDays < 7) {
            return date.toLocaleDateString([], { weekday: 'short' }) + ' ' +
                   date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        }
        return date.toLocaleString([], {
            month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
        });
    }

    /**
     * Escapes a string so it is safe to drop into HTML (attribute or text).
     * The dashboard's `trace.id`, `trace.name`, and `trace.tags` come from a
     * remote agent and must never be trusted as already-encoded.
     */
    escapeHtml(value) {
        if (value == null) return '';
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    renderTraceList() {
        const filtered = this.traces.filter(trace => {
            // Kind filter (client-side)
            if (this.kindFilter && (trace.kind || '') !== this.kindFilter) return false;
            // Search filter (existing)
            if (!this.searchQuery) return true;
            const haystack = [
                trace.name, trace.id, trace.status, trace.pipelineId
            ].filter(Boolean).join(' ').toLowerCase();
            return haystack.includes(this.searchQuery);
        });

        const pill = this.elements.traceCount;
        const filterActive = Boolean(this.searchQuery || this.statusFilter || this.kindFilter);
        const visible = filtered.length;
        // `unfilteredTotalTraces` is the most recent count of every trace in
        // the tenant, which is what users expect to see in the denominator
        // when a filter is active. We never render the server's `total` here
        // directly because it is scoped to the current filter query.
        const total = this.unfilteredTotalTraces || this.totalTraces;
        if (filterActive && visible !== total) {
            pill.textContent = `${visible} / ${total}`;
            pill.classList.add('filtered');
        } else {
            pill.textContent = String(visible);
            pill.classList.remove('filtered');
        }

        if (filtered.length === 0) {
            const noFilter = this.traces.length === 0;
            this.elements.traceList.innerHTML = `
                <div class="empty-message">
                    <div class="empty-emoji">${noFilter ? '📭' : '🔍'}</div>
                    <div>${noFilter ? 'No traces have been recorded yet.' : 'No traces match the current filters.'}</div>
                </div>
            `;
            return;
        }

        const html = filtered.map(trace => this._renderTraceItem(trace)).join('');
        this.elements.traceList.innerHTML = html;
    }

    _renderTraceItem(trace) {
        const id = this.escapeHtml(trace.id);
        const name = this.escapeHtml(trace.name || '(unnamed)');
        const titleAttr = this.escapeHtml(trace.name || trace.id);
        const rawStatus = (trace.status || 'UNKNOWN').toUpperCase();
        const statusClass = `status-${['SUCCESS', 'FAILURE', 'PENDING'].includes(rawStatus) ? rawStatus : 'UNKNOWN'}`;
        const shortId = (trace.id || '').substring(0, 8);
        const tagsHtml = this._renderTraceTags(trace.tags);
        const isActive = this.activeTraceId === trace.id ? 'active' : '';
        const kindBadge = this._renderKindBadge(trace.kind);

        return `
            <div class="trace-item ${isActive}" data-trace-id="${id}">
                <div class="trace-header">
                    ${kindBadge}
                    <span class="trace-name" title="${titleAttr}">${name}</span>
                    <span class="trace-status ${statusClass}">${this.escapeHtml(rawStatus)}</span>
                </div>
                <div class="trace-meta">
                    <span class="trace-id">#${this.escapeHtml(shortId)}</span>
                    <span>${this.escapeHtml(this.formatTime(trace.timestamp))}</span>
                </div>
                ${tagsHtml}
                <button type="button" class="trace-delete" data-action="delete" data-trace-id="${id}" aria-label="Delete trace ${titleAttr}" title="Delete trace">×</button>
            </div>
        `;
    }

    _renderTraceTags(tags) {
        if (!tags || typeof tags !== 'object') return '';
        const entries = Object.entries(tags);
        if (entries.length === 0) return '';
        const items = entries.map(([k, v]) => {
            const key = this.escapeHtml(k);
            const val = this.escapeHtml(v);
            return `<span class="trace-tag">${key}:${val}</span>`;
        }).join('');
        return `<div class="trace-tags">${items}</div>`;
    }

    _renderKindBadge(kind) {
        if (!kind) return '';
        const safeKind = String(kind).toLowerCase().replace(/[^a-z0-9_-]/g, '');
        if (!safeKind) return '';
        return `<span class="trace-kind-badge kind-${safeKind}">${safeKind}</span>`;
    }

    // ----------------------- Click delegation -----------------------

    // The trace list HTML is regenerated on every render, so instead of
    // attaching a fresh onclick handler per item we delegate clicks from
    // the list container. This also dodges the XSS risk of interpolating
    // untrusted `trace.id` into an `onclick="..."` string.
    bindTraceListClicks() {
        // (Re)bound once during construction; the list element is stable.
    }

    handleTraceListClick(event) {
        const deleteBtn = event.target.closest('[data-action="delete"]');
        if (deleteBtn) {
            event.stopPropagation();
            const id = deleteBtn.dataset.traceId;
            if (id) this.deleteTraceById(id);
            return;
        }
        const item = event.target.closest('.trace-item');
        if (item) {
            const id = item.dataset.traceId;
            if (id) this.loadTrace(id);
        }
    }

    async loadTrace(id) {
        if (!id) return;
        this.activeTraceId = id;
        this.renderTraceList();
        this.elements.emptyState.style.display = 'none';
        this.elements.contentHeader.textContent = `Loading trace ${id}...`;
        this.elements.deleteTraceBtn.hidden = false;
        this.elements.openRawBtn.hidden = false;
        this._renderActiveTags(null);

        try {
            const res = await fetch(`${this.baseUrl}/api/traces/${encodeURIComponent(id)}`, {
                headers: { 'Authorization': `Bearer ${this.sessionToken}` }
            });
            if (res.status === 401) {
                this.logout();
                return;
            }
            if (res.status === 404) {
                throw new Error('Trace not found');
            }
            if (!res.ok) {
                throw new Error(`HTTP ${res.status}`);
            }
            const trace = await res.json();
            this.activeTraceId = trace.pipelineId || id;
            this.activeTraceName = trace.name || trace.pipelineId;
            this.elements.contentHeader.textContent = `Trace: ${trace.name || trace.pipelineId}  [${trace.pipelineId}]`;
            this._renderActiveTags(trace.tags);
            this.elements.traceFrame.style.display = 'block';

            const html = trace.htmlContent && trace.htmlContent.trim().length > 0
                ? trace.htmlContent
                : this._emptyTraceHtml(trace);

            if ('srcdoc' in this.elements.traceFrame) {
                this.elements.traceFrame.srcdoc = html;
            } else {
                const frameDoc = this.elements.traceFrame.contentDocument || this.elements.traceFrame.contentWindow.document;
                frameDoc.open();
                frameDoc.write(html);
                frameDoc.close();
            }
        } catch (err) {
            console.error('Failed to load trace:', err);
            this.elements.contentHeader.textContent = `Error loading trace`;
            this.elements.traceFrame.style.display = 'none';
            this._renderEmptyState({
                icon: '⚠️',
                message: err.message || 'Could not load trace',
                error: true,
                action: { label: 'Retry', onClick: () => this.loadTrace(id) }
            });
        }
    }

    _renderActiveTags(tags) {
        // Reuse the trace-tag styling for the active-trace header so users
        // can see the assigned metadata without opening the JSON.
        const headerEl = this.elements.contentHeader;
        const headerBar = headerEl.parentNode; // the .content-header <div>
        const contentArea = headerBar.parentNode; // the .content <div>
        // Remove the previous tags row if any.
        const old = contentArea.querySelector('.active-tags');
        if (old) old.remove();
        if (!tags || typeof tags !== 'object') return;
        const entries = Object.entries(tags);
        if (entries.length === 0) return;
        const row = document.createElement('div');
        row.className = 'active-tags';
        for (const [k, v] of entries) {
            const chip = document.createElement('span');
            chip.className = 'trace-tag';
            chip.textContent = `${k}:${v}`;
            row.appendChild(chip);
        }
        // Insert the row between the header bar and the iframe/empty-state
        // so it lives in the .content flex column and doesn't break the
        // header's own internal flex layout.
        contentArea.insertBefore(row, headerBar.nextSibling);
    }

    _emptyTraceHtml(trace) {
        const name = this.escapeHtml(trace && trace.name ? trace.name : '(unnamed)');
        const status = this.escapeHtml(trace && trace.status ? trace.status : 'UNKNOWN');
        return `<!doctype html><html><head><meta charset="utf-8"><title>Empty trace</title>
<style>
body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0f111a; color: #e2e8f0; display: flex; align-items: center; justify-content: center; height: 100vh; }
.box { text-align: center; max-width: 360px; padding: 24px; }
h1 { font-size: 1.1rem; color: #94a3b8; margin: 0 0 8px; }
p { font-size: 0.9rem; color: #64748b; margin: 0; }
</style></head><body>
<div class="box">
<h1>No visualization payload</h1>
<p>Trace <strong>${name}</strong> has status <strong>${status}</strong> but no <code>htmlContent</code> was provided.</p>
</div>
</body></html>`;
    }
}

// Single global instance used by the inline `onclick` handlers and for
// cross-frame debugging.
let app;
document.addEventListener('DOMContentLoaded', () => {
    app = new TraceDashboard();
    // Click delegation for the trace list. Safe to bind once: the list
    // container is stable, only its inner HTML changes on re-render.
    const list = document.getElementById('traceList');
    if (list) {
        list.addEventListener('click', (e) => app.handleTraceListClick(e));
    }
});
