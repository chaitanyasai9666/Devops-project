package com.example.nlp;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@SpringBootApplication
@RestController
public class NlpApplication {

    static final int MAX_CHARS = 10_000;

    // ponytail: tiny lexicon sentiment, swap in the full AFINN/VADER list or an OpenNLP model when accuracy matters
    static final Set<String> POSITIVE = Set.of("good", "great", "excellent", "amazing", "love", "like", "happy",
            "fast", "awesome", "best", "nice", "wonderful", "fantastic", "easy", "reliable", "helpful", "perfect",
            "enjoy", "smooth", "beautiful", "success", "works");
    static final Set<String> NEGATIVE = Set.of("bad", "terrible", "awful", "hate", "slow", "worst", "poor", "sad",
            "angry", "broken", "bug", "crash", "fail", "error", "difficult", "horrible", "useless", "ugly",
            "annoying", "down");
    static final Set<String> NEGATORS = Set.of("not", "no", "never", "don't", "doesn't", "isn't", "wasn't",
            "aren't", "can't", "won't", "didn't");
    static final Set<String> STOPWORDS = Set.of("the", "and", "but", "are", "was", "were", "for", "with", "this",
            "that", "you", "they", "she", "our", "your", "very", "has", "have", "had", "its", "from", "all", "can",
            "will", "just", "then", "than", "also", "into", "about", "there", "their", "what", "which", "when");

    record Request(String text) {}

    record Analysis(int sentences, int tokens, String sentiment, double score, List<String> keywords, long micros) {}

    public static void main(String[] args) {
        SpringApplication.run(NlpApplication.class, args);
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @PostMapping("/api/analyze")
    Analysis analyze(@RequestBody Request req) {
        if (req.text() == null || req.text().isBlank() || req.text().length() > MAX_CHARS)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text must be 1-" + MAX_CHARS + " characters");
        return analyze(req.text());
    }

    static Analysis analyze(String text) {
        long start = System.nanoTime();
        List<String> words = segment(BreakIterator.getWordInstance(Locale.ENGLISH), text.replace('’', '\''))
                .stream()
                .filter(w -> Character.isLetterOrDigit(w.codePointAt(0)))
                .map(w -> w.toLowerCase(Locale.ROOT))
                .toList();

        double raw = 0;
        for (int i = 0; i < words.size(); i++) {
            int polarity = polarity(words.get(i));
            // "not good", "never really fast": a negator in the previous 3 words flips polarity
            for (int j = Math.max(0, i - 3); j < i && polarity != 0; j++)
                if (NEGATORS.contains(words.get(j))) {
                    polarity = -polarity;
                    break;
                }
            raw += polarity;
        }
        double score = raw / Math.sqrt(raw * raw + 15); // VADER-style normalisation to [-1, 1]
        String sentiment = score >= 0.05 ? "positive" : score <= -0.05 ? "negative" : "neutral";

        Map<String, Long> freq = words.stream()
                .filter(w -> w.length() > 2 && !STOPWORDS.contains(w) && !NEGATORS.contains(w))
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
        List<String> keywords = freq.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()) // stable: ties keep text order
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();

        return new Analysis(segment(BreakIterator.getSentenceInstance(Locale.ENGLISH), text).size(), words.size(),
                sentiment, Math.round(score * 1000) / 1000.0, keywords, (System.nanoTime() - start) / 1000);
    }

    // Lexicon lookup that also matches simple inflections: crashed -> crash, loved -> love, bugs -> bug
    static int polarity(String word) {
        for (String suffix : new String[] {"", "s", "d", "ed", "ing"}) {
            if (!word.endsWith(suffix)) continue;
            String stem = word.substring(0, word.length() - suffix.length());
            if (POSITIVE.contains(stem)) return 1;
            if (NEGATIVE.contains(stem)) return -1;
        }
        return 0;
    }

    static List<String> segment(BreakIterator it, String text) {
        it.setText(text);
        List<String> out = new ArrayList<>();
        for (int s = it.first(), e = it.next(); e != BreakIterator.DONE; s = e, e = it.next()) {
            String seg = text.substring(s, e).strip();
            if (!seg.isEmpty()) out.add(seg);
        }
        return out;
    }
}
