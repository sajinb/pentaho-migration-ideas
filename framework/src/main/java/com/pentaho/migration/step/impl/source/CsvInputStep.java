package com.pentaho.migration.step.impl.source;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractSourceStep;

import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Map;

/**
 * Reads rows from a delimited CSV file.
 * Supports both local filesystem and remote SFTP sources.
 *
 * <h3>Local params</h3>
 * <ul>
 *   <li>{@code filePath}  — path to the local CSV file (required when no sftpHost)
 *   <li>{@code hasHeader} — "true" (default) to skip header; "false" to include it
 * </ul>
 *
 * <h3>SFTP params (when {@code sftpHost} is present)</h3>
 * <ul>
 *   <li>{@code sftpHost}                — SFTP server hostname (enables SFTP mode)
 *   <li>{@code sftpPort}                — port (default: 22)
 *   <li>{@code sftpUser}                — SSH username
 *   <li>{@code sftpPassword}            — password (or use private key)
 *   <li>{@code sftpPrivateKeyPath}      — path to private key file (PEM/OpenSSH)
 *   <li>{@code sftpPrivateKeyPassphrase}— passphrase for encrypted private key
 *   <li>{@code sftpKnownHostsPath}      — known_hosts file (defaults to ~/.ssh/known_hosts)
 *   <li>{@code sftpStrictHostChecking}  — "true" (default) / "false"
 *   <li>{@code filePath}                — absolute path of the CSV on the SFTP server
 * </ul>
 */
public class CsvInputStep extends AbstractSourceStep {

    private String  filePath;
    private boolean hasHeader = true;
    private Map<String, String> params;

    @Override
    public void configure(Map<String, String> params) {
        this.params = params;
        filePath  = params.get("filePath");
        hasHeader = !"false".equalsIgnoreCase(params.getOrDefault("hasHeader", "true"));
    }

    @Override
    protected Iterator<Row> readRows() throws Exception {
        if (SftpSource.isSftp(params)) {
            InputStream in = SftpSource.from(params).openStream();
            return CsvUtil.streamRows(in, hasHeader);
        }
        return CsvUtil.streamRows(Paths.get(filePath), hasHeader);
    }
}
