package com.robertalt.raiptv;

import com.google.mlkit.nl.languageid.*;
import com.google.mlkit.nl.translate.*;

public final class InfoTranslator {
    public interface Callback { void done(String text); }
    private InfoTranslator() {}
    public static void translate(String text, String target, Callback cb) {
        if (text == null || text.trim().isEmpty() || target == null) {
            cb.done(text == null ? "" : text);
            return;
        }
        LanguageIdentifier id = LanguageIdentification.getClient();
        id.identifyLanguage(text).addOnSuccessListener(code -> {
            try {
                String source = TranslateLanguage.fromLanguageTag(code);
                String dest = TranslateLanguage.fromLanguageTag(target);
                if (source == null || dest == null || source.equals(dest) || "und".equals(code)) {
                    cb.done(text);
                    id.close();
                    return;
                }
                TranslatorOptions options = new TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(dest)
                    .build();
                Translator translator = Translation.getClient(options);
                translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                    .addOnSuccessListener(v -> translator.translate(text)
                        .addOnSuccessListener(out -> {
                            cb.done(out == null || out.trim().isEmpty() ? text : out);
                            translator.close();
                            id.close();
                        })
                        .addOnFailureListener(e -> {
                            cb.done(text);
                            translator.close();
                            id.close();
                        }))
                    .addOnFailureListener(e -> {
                        cb.done(text);
                        translator.close();
                        id.close();
                    });
            } catch (Exception e) {
                cb.done(text);
                try { id.close(); } catch (Exception ignored) {}
            }
        }).addOnFailureListener(e -> {
            cb.done(text);
            try { id.close(); } catch (Exception ignored) {}
        });
    }
}
