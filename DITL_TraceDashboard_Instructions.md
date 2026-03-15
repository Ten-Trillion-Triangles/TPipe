# TPipe Trace Dashboard Demo

I have created an interactive demo script that boots up the TraceServer and automatically dispatches a few realistic trace examples (including a "Pending" one to show active status) to it.

To see the high-tech trace dashboard in action locally, run the following command in your terminal from the root of the project:

```bash
gradle :TPipe-TraceServer:run
```

Once the server says it's started, open your web browser and navigate to:
**http://localhost:8081**

When prompted for the Authorization Key by the security overlay, enter:
**demo123**

You'll be able to see:
- A real-time updating list of traces in the sidebar.
- "Live" WebSockets polling.
- The interactive microservice traces embedded into the dark mode dashboard when you click them.
