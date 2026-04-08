import { useState, useEffect, useCallback } from 'react';
import { useParams, Link }                   from 'react-router-dom';
import {
  getProject,
  convertProject,
  executeProject,
  getExecution,
} from '../api.js';
import StatusBadge from '../components/StatusBadge.jsx';

const ACTIVE_STATUSES = new Set(['PENDING', 'RUNNING']);

export default function ProjectDetail() {
  const { id }   = useParams();
  const [project,  setProject]  = useState(null);
  const [loading,  setLoading]  = useState(true);
  const [error,    setError]    = useState('');
  const [converting, setConverting] = useState(false);
  const [executing,  setExecuting]  = useState(false);
  const [expandedYaml, setExpandedYaml] = useState(null);

  // ── Load project ────────────────────────────────────────────────────────────

  const load = useCallback(() => {
    getProject(id)
      .then(p => { setProject(p); setError(''); })
      .catch(ex => setError(ex.response?.data?.message ?? 'Failed to load project.'))
      .finally(() => setLoading(false));
  }, [id]);

  useEffect(() => { load(); }, [load]);

  // ── Poll while any execution is PENDING/RUNNING ──────────────────────────

  useEffect(() => {
    if (!project) return;
    const hasActive = project.executions?.some(e => ACTIVE_STATUSES.has(e.status));
    if (!hasActive) return;

    const interval = setInterval(async () => {
      // Only refresh the running executions to avoid hammering the server
      const updated = await getProject(id).catch(() => null);
      if (updated) {
        setProject(updated);
        const stillActive = updated.executions?.some(e => ACTIVE_STATUSES.has(e.status));
        if (!stillActive) clearInterval(interval);
      }
    }, 2000);

    return () => clearInterval(interval);
  }, [id, project]);

  // ── Actions ──────────────────────────────────────────────────────────────

  const handleConvert = async () => {
    setConverting(true);
    setError('');
    try {
      const updated = await convertProject(id);
      setProject(updated);
    } catch (ex) {
      setError(ex.response?.data?.message ?? 'Conversion failed.');
    } finally {
      setConverting(false);
    }
  };

  const handleExecute = async () => {
    setExecuting(true);
    setError('');
    try {
      await executeProject(id);
      // Re-load to pick up the new execution record
      load();
    } catch (ex) {
      setError(ex.response?.data?.message ?? 'Execution failed to start.');
    } finally {
      setExecuting(false);
    }
  };

  // ── Render ───────────────────────────────────────────────────────────────

  if (loading)  return <div className="page"><div className="empty-state">Loading…</div></div>;
  if (!project) return <div className="page"><div className="alert-error">{error}</div></div>;

  const canConvert = ['UPLOADED', 'CONVERSION_FAILED'].includes(project.status);
  const canExecute = project.status === 'CONVERTED';
  const hasActiveExec = project.executions?.some(e => ACTIVE_STATUSES.has(e.status));

  return (
    <div className="page">
      {/* ── Breadcrumb ── */}
      <div className="breadcrumb">
        <Link to="/">Projects</Link>
        <span className="breadcrumb-sep">›</span>
        <span>{project.name}</span>
      </div>

      {/* ── Header ── */}
      <div className="page-header">
        <div>
          <h1 className="page-title">{project.name}</h1>
          <div className="page-meta">
            <StatusBadge status={project.status} />
            <span className="text-muted">Created {fmtDate(project.createdAt)}</span>
          </div>
        </div>
      </div>

      {error && <div className="alert-error" style={{ marginBottom: 16 }}>{error}</div>}

      {/* ── Uploaded Files ── */}
      <section className="section">
        <h2 className="section-title">Uploaded Files</h2>
        <div className="card">
          <table className="table">
            <thead>
              <tr><th>Filename</th><th>Type</th><th>Size</th></tr>
            </thead>
            <tbody>
              {project.files.map(f => (
                <tr key={f.id}>
                  <td className="font-mono">{f.filename}</td>
                  <td><span className={`badge ${f.fileType === 'KJB' ? 'badge-blue' : 'badge-gray'}`}>{f.fileType}</span></td>
                  <td className="text-muted">{fmtSize(f.sizeBytes)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {/* ── YAML Generation ── */}
      <section className="section">
        <div className="section-header">
          <h2 className="section-title">YAML Generation</h2>
          <button
            className="btn btn-secondary"
            onClick={handleConvert}
            disabled={!canConvert || converting}
          >
            {converting ? '⏳ Generating…' : '⚙ Generate YAML'}
          </button>
        </div>

        {project.status === 'CONVERSION_FAILED' && project.errorMessage && (
          <div className="alert-error" style={{ marginBottom: 12 }}>
            {project.errorMessage}
          </div>
        )}

        {project.yamlDefinitions.length > 0 ? (
          <div className="yaml-list">
            {project.yamlDefinitions.map(y => (
              <div key={y.id} className="yaml-item">
                <div
                  className="yaml-header"
                  onClick={() => setExpandedYaml(expandedYaml === y.id ? null : y.id)}
                >
                  <div className="yaml-name">
                    <span className="file-icon">📄</span>
                    <span className="font-mono">{y.filename}</span>
                    <span className={`badge ${y.definitionType === 'JOB' ? 'badge-blue' : 'badge-gray'}`}>
                      {y.definitionType}
                    </span>
                  </div>
                  <div className="yaml-meta">
                    <span className="text-muted">{y.contentLength.toLocaleString()} chars</span>
                    <span className="expand-icon">{expandedYaml === y.id ? '▲' : '▼'}</span>
                  </div>
                </div>
                {expandedYaml === y.id && (
                  <pre className="yaml-content">{y.content}</pre>
                )}
              </div>
            ))}
          </div>
        ) : (
          <div className="empty-state-inline">
            {canConvert
              ? 'Click "Generate YAML" to convert the uploaded files.'
              : 'No YAML definitions yet.'}
          </div>
        )}
      </section>

      {/* ── Job Execution ── */}
      <section className="section">
        <div className="section-header">
          <h2 className="section-title">Job Execution</h2>
          <button
            className="btn btn-primary"
            onClick={handleExecute}
            disabled={!canExecute || executing || hasActiveExec}
          >
            {executing || hasActiveExec ? '⏳ Running…' : '▶ Run Job'}
          </button>
        </div>

        {!canExecute && project.status !== 'CONVERTED' && (
          <div className="empty-state-inline">
            Generate YAML first before running the job.
          </div>
        )}

        {project.executions.length > 0 ? (
          <div className="card">
            <table className="table">
              <thead>
                <tr>
                  <th>Execution ID</th>
                  <th>Status</th>
                  <th>Started</th>
                  <th>Duration</th>
                  <th>Error</th>
                </tr>
              </thead>
              <tbody>
                {project.executions.map(e => (
                  <tr key={e.id}>
                    <td className="font-mono text-muted">{e.id.split('-')[0]}…</td>
                    <td><StatusBadge status={e.status} /></td>
                    <td className="text-muted">{e.startedAt ? fmtDate(e.startedAt) : '—'}</td>
                    <td className="text-muted">
                      {e.durationMs != null ? `${(e.durationMs / 1000).toFixed(2)}s` : '—'}
                    </td>
                    <td className="text-error" title={e.errorMessage}>
                      {e.errorMessage ? e.errorMessage.slice(0, 60) + (e.errorMessage.length > 60 ? '…' : '') : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="empty-state-inline">No executions yet.</div>
        )}
      </section>
    </div>
  );
}

function fmtDate(iso) {
  if (!iso) return '—';
  return new Date(iso).toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
}

function fmtSize(bytes) {
  if (bytes < 1024)        return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
