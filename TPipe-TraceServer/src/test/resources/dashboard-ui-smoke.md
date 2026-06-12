# Dashboard UI smoke checks (manual / Playwright)

These are the flows verified end-to-end via the Playwright MCP against a
running `TPipe-TraceServer` demo on port 8085. They are not part of the
JUnit suite (no browser dependency on the classpath), but they document
the acceptance criteria the dashboard implementation must continue to meet.

## 1. Auth overlay renders before login
- The page boots with the dark-blue auth overlay covering the entire viewport.
- The "Authentication Required" heading is visible.
- The `+` file picker button has an aria-label (`Load key from file`).
- The textbox is auto-focused (blue focus ring) without an extra click.
- The topbar shows the "TPipe TraceServer" logo on the left and a
  "Connecting..." placeholder for the live indicator on the right.
- The Logout button in the topbar is hidden.

## 2. Login with wrong key shows an inline error
- Type an invalid key, click Connect.
- A red-bordered error panel appears under the Connect button with the
  server's `unauthorized` message; no `alert()` is used.
- The Connect button is re-enabled and accepts another attempt.

## 3. Login with the correct key hides the overlay and renders the dashboard
- The overlay disappears; the topbar shows "Live" with a green dot.
- The sidebar shows the unfiltered count and the trace list.
- A WebSocket connection is established and "Live" stays green.

## 4. Loading a trace shows its content in the iframe
- Click a trace in the sidebar. The row highlights with a left blue border.
- The content header shows `Trace: <name> [<pipelineId>]` plus a tags row.
- The iframe loads the trace HTML (`srcdoc`).
- A Delete (×) button is visible on the active trace on hover.
- Open and Delete action buttons appear in the content header.

## 5. WebSocket live update prepends a new trace
- POST a new trace via `/api/traces` (agent auth).
- The new trace appears at the top of the list with a "just now" timestamp.
- The count increments.

## 6. Search filters the visible list
- Typing in the search box narrows the list as the user types.
- The count pill shows "visible / total" (blue) when filtered.
- The clear (×) button on the right of the search input clears the query.

## 7. Status filter chips scope the list
- Clicking a chip (Success / Failure / Pending) calls the server with
  `?status=<value>` and updates the list.
- The "All" chip clears the filter.
- Active chip uses the matching accent color (green/red/amber) for its
  border and label.

## 8. Delete removes a trace from the list
- Hover a trace to reveal the × button.
- Clicking the × triggers a `confirm()` dialog; confirming fires
  `DELETE /api/traces/{id}`.
- The trace is removed from the local list and the count decreases.
- If the active trace is deleted, the content area returns to the
  empty state.

## 9. Logout clears state and reopens the auth overlay
- The Logout button in the topbar clears the session and refresh tokens
  from localStorage, closes the WebSocket, and shows the auth overlay
  again.

## 10. Mobile viewport
- At <=700px the sidebar shrinks to 260px, the topbar compresses, and
  the content-header buttons drop their text labels (icon-only).
- The trace list and iframe remain readable; nothing overflows off-screen.
