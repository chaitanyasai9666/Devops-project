package com.example.nlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest // also proves the Spring context starts
class NlpApplicationTests {

    @Test
    void detectsSentimentWithNegation() {
        assertEquals("positive", NlpApplication.analyze("I love this API. It is fast and reliable!").sentiment());
        assertEquals("negative", NlpApplication.analyze("This is not good, the build failed again.").sentiment());
        assertEquals("negative", NlpApplication.analyze("I don’t like it.").sentiment());
        assertEquals("negative", NlpApplication.analyze("The old server always crashed.").sentiment());
        assertEquals("neutral", NlpApplication.analyze("The server runs on port 8080.").sentiment());
    }

    @Test
    void countsSentencesTokensAndKeywords() {
        var a = NlpApplication.analyze("Docker builds images. Kubernetes runs images!");
        assertEquals(2, a.sentences());
        assertEquals(6, a.tokens());
        assertEquals("images", a.keywords().get(0));
    }

    @Test
    void rejectsBlankAndOversizedText() {
        var app = new NlpApplication();
        assertThrows(ResponseStatusException.class, () -> app.analyze(new NlpApplication.Request(" ")));
        assertThrows(ResponseStatusException.class, () -> app.analyze(new NlpApplication.Request(null)));
        assertThrows(ResponseStatusException.class,
                () -> app.analyze(new NlpApplication.Request("a".repeat(NlpApplication.MAX_CHARS + 1))));
    }
}
