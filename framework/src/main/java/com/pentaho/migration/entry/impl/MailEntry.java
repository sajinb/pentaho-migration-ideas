package com.pentaho.migration.entry.impl;

import com.pentaho.migration.entry.JobEntry;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Map;
import java.util.Properties;

/**
 * Sends an email via SMTP.
 * Equivalent to Pentaho's Mail job entry.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code host}       — SMTP host (required)</li>
 *   <li>{@code port}       — SMTP port (default: 25 plain, 465 SSL, 587 STARTTLS)</li>
 *   <li>{@code from}       — sender address (required)</li>
 *   <li>{@code to}         — recipient address(es), comma-separated (required)</li>
 *   <li>{@code subject}    — email subject</li>
 *   <li>{@code body}       — email body (plain text)</li>
 *   <li>{@code user}       — SMTP auth username (optional)</li>
 *   <li>{@code password}   — SMTP auth password (optional)</li>
 *   <li>{@code useSSL}     — {@code "true"} to use SSL/TLS (default false)</li>
 *   <li>{@code useSTARTTLS}— {@code "true"} to use STARTTLS (default false)</li>
 * </ul>
 */
public class MailEntry implements JobEntry {

    private String  host;
    private int     port;
    private String  from;
    private String  to;
    private String  subject;
    private String  body;
    private String  user;
    private String  password;
    private boolean useSSL;
    private boolean useSTARTTLS;

    @Override
    public void configure(Map<String, String> params) {
        host    = params.get("host");
        if (host == null || host.isBlank())
            throw new IllegalArgumentException("MailEntry: 'host' param is required.");
        from    = params.get("from");
        if (from == null || from.isBlank())
            throw new IllegalArgumentException("MailEntry: 'from' param is required.");
        to      = params.get("to");
        if (to == null || to.isBlank())
            throw new IllegalArgumentException("MailEntry: 'to' param is required.");

        useSSL      = "true".equalsIgnoreCase(params.get("useSSL"));
        useSTARTTLS = "true".equalsIgnoreCase(params.get("useSTARTTLS"));

        int defaultPort = useSSL ? 465 : (useSTARTTLS ? 587 : 25);
        String portStr  = params.get("port");
        port = (portStr != null && !portStr.isBlank()) ? Integer.parseInt(portStr) : defaultPort;

        subject  = params.getOrDefault("subject", "");
        body     = params.getOrDefault("body", "");
        user     = params.get("user");
        password = params.get("password");
    }

    @Override
    public boolean execute(Map<String, String> context) throws Exception {
        Properties props = buildMailProperties();

        Session session;
        if (user != null && !user.isBlank() && password != null) {
            final String authUser = user;
            final String authPass = password;
            session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(authUser, authPass);
                }
            });
        } else {
            session = Session.getInstance(props);
        }

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        message.setSubject(subject);
        message.setText(body);

        Transport.send(message);
        return true;
    }

    private Properties buildMailProperties() {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));

        if (useSSL) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.port", String.valueOf(port));
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        }

        if (useSTARTTLS) {
            props.put("mail.smtp.starttls.enable", "true");
        }

        boolean needsAuth = user != null && !user.isBlank() && password != null;
        props.put("mail.smtp.auth", String.valueOf(needsAuth));

        // Connection / read timeout (30 s)
        props.put("mail.smtp.connectiontimeout", "30000");
        props.put("mail.smtp.timeout", "30000");

        return props;
    }
}
