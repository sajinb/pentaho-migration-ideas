import { useState, useEffect } from 'react';
import { useNavigate }         from 'react-router-dom';
import { listProjects }        from '../api.js';
import StatusBadge             from '../components/StatusBadge.jsx';
import UploadModal             from '../components/UploadModal.jsx';

export default function ProjectsPage() {
  const [projects,  setProjects]  = useState([]);
  const [loading,   setLoading]   = useState(true);
  const [showModal, setShowModal] = useState(false);
  const navigate = useNavigate();

  const load = () => {
    setLoading(true);
    listProjects()
      .then(setProjects)
      .catch(console.error)
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  const handleUploaded = (project) => {
    setShowModal(false);
    navigate(`/projects/${project.id}`);
  };

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">Projects</h1>
          <p className="page-subtitle">
            Each project is one Pentaho job (.kjb) and its associated transformation files (.ktr).
          </p>
        </div>
        <button className="btn btn-primary" onClick={() => setShowModal(true)}>
          + New Project
        </button>
      </div>

      {loading ? (
        <div className="empty-state">Loading…</div>
      ) : projects.length === 0 ? (
        <div className="empty-state">
          <div className="empty-icon">📦</div>
          <p>No projects yet.</p>
          <button className="btn btn-primary" onClick={() => setShowModal(true)}>
            Upload your first project
          </button>
        </div>
      ) : (
        <div className="card">
          <table className="table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Status</th>
                <th>Files</th>
                <th>YAMLs</th>
                <th>Latest Execution</th>
                <th>Created</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {projects.map(p => (
                <tr key={p.id} className="table-row-clickable" onClick={() => navigate(`/projects/${p.id}`)}>
                  <td className="table-name">{p.name}</td>
                  <td><StatusBadge status={p.status} /></td>
                  <td className="table-num">{p.fileCount}</td>
                  <td className="table-num">{p.yamlCount}</td>
                  <td>
                    {p.latestExecution
                      ? <StatusBadge status={p.latestExecution.status} />
                      : <span className="text-muted">—</span>
                    }
                  </td>
                  <td className="text-muted">{fmtDate(p.createdAt)}</td>
                  <td>
                    <button
                      className="btn btn-ghost btn-sm"
                      onClick={e => { e.stopPropagation(); navigate(`/projects/${p.id}`); }}
                    >
                      View →
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {showModal && (
        <UploadModal
          onClose={() => setShowModal(false)}
          onUploaded={handleUploaded}
        />
      )}
    </div>
  );
}

function fmtDate(iso) {
  if (!iso) return '—';
  return new Date(iso).toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit',
  });
}
