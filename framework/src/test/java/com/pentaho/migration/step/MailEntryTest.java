package com.pentaho.migration.step;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.pentaho.migration.entry.impl.MailEntry;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link MailEntry} using an embedded GreenMail SMTP server.
 *
 * <p>GreenMail binds to a random free port each test run (ServerSetup with port 0),
 * avoiding conflicts with system services. Each test registers fresh credentials.
 */
class MailEntryTest {

    // Random port; withPerMethodLifecycle(true) resets state between tests automatically.
    @RegisterExtension
    final GreenMailExtension greenMail = new GreenMailExtension(
            new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP))
            .withConfiguration(GreenMailConfiguration.aConfig()
                    .withUser("sender@test.com", "secret"))
            .withPerMethodLifecycle(true);

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Map<String, String> baseParams() {
        Map<String, String> p = new HashMap<>();
        p.put("host",     "127.0.0.1");
        p.put("port",     String.valueOf(greenMail.getSmtp().getPort()));
        p.put("from",     "sender@test.com");
        p.put("to",       "receiver@test.com");
        p.put("user",     "sender@test.com");
        p.put("password", "secret");
        return p;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void sendSimpleEmail_deliveredToGreenMail() throws Exception {
        Map<String, String> params = baseParams();
        params.put("subject", "Hello from MailEntry");
        params.put("body",    "Test body content");

        MailEntry entry = new MailEntry();
        entry.configure(params);
        boolean ok = entry.execute(new HashMap<>());

        assertTrue(ok, "execute() should return true on success");
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length, "exactly one message should be received");
        assertEquals("Hello from MailEntry", messages[0].getSubject());
        assertEquals("sender@test.com", messages[0].getFrom()[0].toString());
    }

    @Test
    void sendEmail_bodyContent_matches() throws Exception {
        Map<String, String> params = baseParams();
        params.put("subject", "Body Test");
        params.put("body",    "This is the body text.");

        MailEntry entry = new MailEntry();
        entry.configure(params);
        entry.execute(new HashMap<>());

        MimeMessage msg = greenMail.getReceivedMessages()[0];
        String body = msg.getContent().toString();
        assertTrue(body.contains("This is the body text."), "body content should match");
    }

    @Test
    void sendEmail_emptySubjectAndBody_succeeds() throws Exception {
        Map<String, String> params = baseParams();
        // subject and body intentionally omitted — defaults to empty strings

        MailEntry entry = new MailEntry();
        entry.configure(params);
        boolean ok = entry.execute(new HashMap<>());

        assertTrue(ok);
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length);
        assertEquals("", messages[0].getSubject());
    }

    @Test
    void configure_missingHost_throwsIllegalArgument() {
        MailEntry entry = new MailEntry();
        Map<String, String> p = new HashMap<>();
        p.put("from", "a@b.com");
        p.put("to",   "c@d.com");
        assertThrows(IllegalArgumentException.class, () -> entry.configure(p),
                "should throw when 'host' is absent");
    }

    @Test
    void configure_missingFrom_throwsIllegalArgument() {
        MailEntry entry = new MailEntry();
        Map<String, String> p = new HashMap<>();
        p.put("host", "localhost");
        p.put("to",   "c@d.com");
        assertThrows(IllegalArgumentException.class, () -> entry.configure(p),
                "should throw when 'from' is absent");
    }

    @Test
    void configure_missingTo_throwsIllegalArgument() {
        MailEntry entry = new MailEntry();
        Map<String, String> p = new HashMap<>();
        p.put("host", "localhost");
        p.put("from", "a@b.com");
        assertThrows(IllegalArgumentException.class, () -> entry.configure(p),
                "should throw when 'to' is absent");
    }

    @Test
    void sendMultipleEmails_allDelivered() throws Exception {
        MailEntry entry = new MailEntry();
        entry.configure(baseParams());

        entry.execute(new HashMap<>());
        entry.execute(new HashMap<>());
        entry.execute(new HashMap<>());

        assertEquals(3, greenMail.getReceivedMessages().length,
                "three separate sends should deliver three messages");
    }
}
