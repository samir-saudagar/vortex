export function App() {
  return (
    <main className="page-shell">
      <section className="status-card" aria-labelledby="app-title">
        <p className="eyebrow">LOCAL DEVELOPMENT</p>
        <h1 id="app-title">VORTEX</h1>
        <p className="intro">Baggage routing and journey tracking</p>
        <div className="phase-status" role="status">
          <span className="status-dot" aria-hidden="true" />
          <span>Phase 0 foundation is running</span>
        </div>
        <p className="details">
          Check-in, route planning, equipment simulation, and live tracking
          arrive in later implementation phases.
        </p>
      </section>
    </main>
  );
}
