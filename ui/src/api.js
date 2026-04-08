import axios from 'axios';

const http = axios.create({ baseURL: '/api' });

// ── Projects ──────────────────────────────────────────────────────────────────

export const listProjects = () =>
  http.get('/projects').then(r => r.data);

export const getProject = (id) =>
  http.get(`/projects/${id}`).then(r => r.data);

/**
 * Upload a new project.
 * @param {string} name
 * @param {File}   kjbFile
 * @param {File[]} ktrFiles
 */
export const uploadProject = (name, kjbFile, ktrFiles) => {
  const form = new FormData();
  form.append('name', name);
  form.append('kjb',  kjbFile);
  ktrFiles.forEach(f => form.append('ktrs', f));
  return http.post('/projects', form).then(r => r.data);
};

export const convertProject = (id) =>
  http.post(`/projects/${id}/convert`).then(r => r.data);

export const executeProject = (id) =>
  http.post(`/projects/${id}/execute`).then(r => r.data);

// ── Executions ────────────────────────────────────────────────────────────────

export const listExecutions = (projectId) =>
  http.get(`/projects/${projectId}/executions`).then(r => r.data);

export const getExecution = (projectId, execId) =>
  http.get(`/projects/${projectId}/executions/${execId}`).then(r => r.data);
