const CONFIG = {
  UPLOADED:           { label: 'Uploaded',          cls: 'badge-gray'   },
  CONVERTING:         { label: 'Converting…',        cls: 'badge-yellow badge-pulse' },
  CONVERTED:          { label: 'Converted',          cls: 'badge-blue'   },
  CONVERSION_FAILED:  { label: 'Conversion Failed',  cls: 'badge-red'    },
  PENDING:            { label: 'Pending',            cls: 'badge-gray'   },
  RUNNING:            { label: 'Running…',           cls: 'badge-yellow badge-pulse' },
  COMPLETED:          { label: 'Completed',          cls: 'badge-green'  },
  FAILED:             { label: 'Failed',             cls: 'badge-red'    },
};

export default function StatusBadge({ status }) {
  const { label, cls } = CONFIG[status] ?? { label: status, cls: 'badge-gray' };
  return <span className={`badge ${cls}`}>{label}</span>;
}
