package com.pentaho.migration.step.impl.source;

import com.jcraft.jsch.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Opens a file over SFTP and returns it as an {@link InputStream}.
 *
 * <p>Call {@link #openStream()} to obtain the stream; closing the stream also
 * closes the SFTP channel and SSH session, so callers <em>must</em> close it.
 *
 * <h3>Required params</h3>
 * <ul>
 *   <li>{@code sftpHost}     — SFTP server hostname or IP
 *   <li>{@code filePath}     — absolute path of the remote file
 *   <li>{@code sftpUser}     — SSH username
 * </ul>
 *
 * <h3>Optional params</h3>
 * <ul>
 *   <li>{@code sftpPort}                — port (default: 22)
 *   <li>{@code sftpPassword}            — password auth (mutually exclusive with key)
 *   <li>{@code sftpPrivateKeyPath}      — path to private key file (PEM/OpenSSH)
 *   <li>{@code sftpPrivateKeyPassphrase}— passphrase for encrypted private key
 *   <li>{@code sftpKnownHostsPath}      — path to known_hosts file
 *                                         (defaults to ~/.ssh/known_hosts if present)
 *   <li>{@code sftpStrictHostChecking}  — "true" (default) / "false" to disable
 *                                         host key verification (dev/test only)
 * </ul>
 */
public final class SftpSource {

    private final String host;
    private final int    port;
    private final String user;
    private final String password;
    private final String privateKeyPath;
    private final String privateKeyPassphrase;
    private final String knownHostsPath;
    private final boolean strictHostChecking;
    private final String remotePath;

    private SftpSource(Map<String, String> params) {
        host                 = require(params, "sftpHost");
        port                 = Integer.parseInt(params.getOrDefault("sftpPort", "22"));
        user                 = require(params, "sftpUser");
        password             = params.get("sftpPassword");
        privateKeyPath       = params.get("sftpPrivateKeyPath");
        privateKeyPassphrase = params.getOrDefault("sftpPrivateKeyPassphrase", "");
        knownHostsPath       = params.get("sftpKnownHostsPath");
        strictHostChecking   = !"false".equalsIgnoreCase(
                                   params.getOrDefault("sftpStrictHostChecking", "true"));
        remotePath           = require(params, "filePath");

        if (password == null && privateKeyPath == null) {
            throw new IllegalArgumentException(
                "SFTP requires either 'sftpPassword' or 'sftpPrivateKeyPath'");
        }
    }

    /**
     * Returns {@code true} if the params map contains {@code sftpHost},
     * indicating SFTP mode should be used.
     */
    public static boolean isSftp(Map<String, String> params) {
        return params.containsKey("sftpHost") && !params.get("sftpHost").isBlank();
    }

    /** Create an {@link SftpSource} from the step params. */
    public static SftpSource from(Map<String, String> params) {
        return new SftpSource(params);
    }

    /**
     * Connects to the SFTP server and opens the remote file for reading.
     * Closing the returned stream disconnects the session.
     */
    public InputStream openStream() throws JSchException, SftpException {
        JSch jsch = new JSch();

        // Host key verification
        if (knownHostsPath != null) {
            jsch.setKnownHosts(knownHostsPath);
        } else {
            String defaultKnownHosts = System.getProperty("user.home") + "/.ssh/known_hosts";
            if (new java.io.File(defaultKnownHosts).exists()) {
                jsch.setKnownHosts(defaultKnownHosts);
            }
        }

        // Authentication
        if (privateKeyPath != null) {
            jsch.addIdentity(privateKeyPath, privateKeyPassphrase.getBytes());
        }

        Session session = jsch.getSession(user, host, port);
        if (password != null) {
            session.setPassword(password);
        }
        if (!strictHostChecking) {
            session.setConfig("StrictHostKeyChecking", "no");
        }
        session.connect();

        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect();

        InputStream remoteStream = channel.get(remotePath);

        // Wrap so closing the stream also tears down the SFTP session
        return new SessionClosingStream(remoteStream, channel, session);
    }

    // -------------------------------------------------------------------------

    private static String require(Map<String, String> params, String key) {
        String v = params.get(key);
        if (v == null || v.isBlank())
            throw new IllegalArgumentException("Missing required SFTP param: " + key);
        return v;
    }

    /** Wraps an SFTP InputStream so that closing it also closes the channel and session. */
    private static final class SessionClosingStream extends InputStream {

        private final InputStream    delegate;
        private final ChannelSftp   channel;
        private final Session        session;
        private volatile boolean     closed = false;

        SessionClosingStream(InputStream delegate, ChannelSftp channel, Session session) {
            this.delegate = delegate;
            this.channel  = channel;
            this.session  = session;
        }

        @Override public int read()                           throws IOException { return delegate.read(); }
        @Override public int read(byte[] b)                   throws IOException { return delegate.read(b); }
        @Override public int read(byte[] b, int off, int len) throws IOException { return delegate.read(b, off, len); }
        @Override public long skip(long n)                    throws IOException { return delegate.skip(n); }
        @Override public int available()                      throws IOException { return delegate.available(); }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try { delegate.close(); } finally {
                try { channel.disconnect(); } finally {
                    session.disconnect();
                }
            }
        }
    }
}
