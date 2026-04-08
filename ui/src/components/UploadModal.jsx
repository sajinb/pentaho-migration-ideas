import { useState, useRef } from 'react';
import { uploadProject }    from '../api.js';

export default function UploadModal({ onClose, onUploaded }) {
  const [name,     setName]     = useState('');
  const [kjbFile,  setKjbFile]  = useState(null);
  const [ktrFiles, setKtrFiles] = useState([]);
  const [loading,  setLoading]  = useState(false);
  const [error,    setError]    = useState('');

  const kjbRef  = useRef();
  const ktrRef  = useRef();

  const validate = () => {
    if (!name.trim())         return 'Project name is required.';
    if (!kjbFile)             return 'A .kjb file is required.';
    if (!kjbFile.name.toLowerCase().endsWith('.kjb'))
                              return 'The job file must have a .kjb extension.';
    if (ktrFiles.length === 0) return 'At least one .ktr file is required.';
    const badKtr = ktrFiles.find(f => !f.name.toLowerCase().endsWith('.ktr'));
    if (badKtr)               return `"${badKtr.name}" is not a .ktr file.`;
    return null;
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    const err = validate();
    if (err) { setError(err); return; }

    setLoading(true);
    setError('');
    try {
      const project = await uploadProject(name.trim(), kjbFile, ktrFiles);
      onUploaded(project);
    } catch (ex) {
      setError(ex.response?.data?.message ?? ex.message ?? 'Upload failed.');
    } finally {
      setLoading(false);
    }
  };

  const handleKtrChange = (e) => {
    setKtrFiles(Array.from(e.target.files));
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Upload New Project</h2>
          <button className="btn-icon" onClick={onClose} aria-label="Close">✕</button>
        </div>

        <form onSubmit={handleSubmit} className="upload-form">

          <div className="field">
            <label htmlFor="proj-name">Project Name <span className="required">*</span></label>
            <input
              id="proj-name"
              type="text"
              value={name}
              onChange={e => setName(e.target.value)}
              placeholder="e.g. daily_customer_etl"
              disabled={loading}
            />
          </div>

          <div className="field">
            <label>
              KJB File (job definition) <span className="required">*</span>
            </label>
            <div
              className={`drop-zone ${kjbFile ? 'drop-zone-filled' : ''}`}
              onClick={() => kjbRef.current.click()}
            >
              {kjbFile
                ? <><span className="file-icon">📄</span> {kjbFile.name}</>
                : <><span className="file-icon">📂</span> Click to select a <code>.kjb</code> file</>
              }
            </div>
            <input
              ref={kjbRef}
              type="file"
              accept=".kjb"
              style={{ display: 'none' }}
              onChange={e => setKjbFile(e.target.files[0] ?? null)}
              disabled={loading}
            />
          </div>

          <div className="field">
            <label>
              KTR Files (transformations) <span className="required">*</span>
              <span className="label-hint"> — select all related .ktr files</span>
            </label>
            <div
              className={`drop-zone ${ktrFiles.length > 0 ? 'drop-zone-filled' : ''}`}
              onClick={() => ktrRef.current.click()}
            >
              {ktrFiles.length > 0
                ? <>
                    <span className="file-icon">📄</span>
                    {ktrFiles.length === 1
                      ? ktrFiles[0].name
                      : `${ktrFiles.length} files: ${ktrFiles.map(f => f.name).join(', ')}`
                    }
                  </>
                : <><span className="file-icon">📂</span> Click to select <code>.ktr</code> files (multi-select allowed)</>
              }
            </div>
            <input
              ref={ktrRef}
              type="file"
              accept=".ktr"
              multiple
              style={{ display: 'none' }}
              onChange={handleKtrChange}
              disabled={loading}
            />
          </div>

          {error && <div className="alert-error">{error}</div>}

          <div className="modal-footer">
            <button type="button" className="btn btn-ghost" onClick={onClose} disabled={loading}>
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={loading}>
              {loading ? 'Uploading…' : 'Upload Project'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
