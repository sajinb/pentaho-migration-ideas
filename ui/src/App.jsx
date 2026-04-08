import { Routes, Route, Link } from 'react-router-dom';
import ProjectsPage    from './pages/ProjectsPage.jsx';
import ProjectDetail   from './pages/ProjectDetail.jsx';

export default function App() {
  return (
    <div className="app">
      <header className="header">
        <div className="header-inner">
          <Link to="/" className="header-logo">
            <span className="logo-icon">⚙</span>
            Pentaho Migration
          </Link>
        </div>
      </header>

      <main className="main-content">
        <Routes>
          <Route path="/"            element={<ProjectsPage />} />
          <Route path="/projects/:id" element={<ProjectDetail />} />
        </Routes>
      </main>
    </div>
  );
}
