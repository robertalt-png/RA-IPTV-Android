package com.nenotv.player.proextras;

import com.nenotv.player.InfoTranslator;
import com.nenotv.player.ExtraPrivacySession;
import com.google.mlkit.common.MlKit;
import com.google.mlkit.nl.languageid.*;
import com.google.mlkit.nl.translate.*;
import com.google.mlkit.common.model.DownloadConditions;
import android.util.LruCache;
import java.util.HashSet;
import java.util.Set;

public final class ProInfoTranslator {
    private static boolean mlKitReady;
    private static final LruCache<String,String> CACHE = new LruCache<>(700);
    private static final Set<Request> pending = new HashSet<>();
    static { ExtraPrivacySession.addListener(ProInfoTranslator::revoke); }
    private ProInfoTranslator() {}
    private static boolean allowed() {
        android.content.Context context = InfoTranslator.context();
        return context != null && com.nenotv.player.storage.ExtraPrivacyStore.allowsSdk(context)
                && new com.nenotv.player.storage.EntitlementStore(context).isPro();
    }
    private static void revoke() {
        synchronized (ExtraPrivacySession.class) {
            CACHE.evictAll();
            for (Request request : pending.toArray(new Request[0])) {
                if (request.generation != ExtraPrivacySession.generation()) {
                    try { request.finish(request.text); } catch (RuntimeException ignored) {}
                }
            }
        }
    }
    public static void translate(String text, String target, InfoTranslator.Callback cb) {
        if (cb == null) return;
        if (text == null || text.trim().isEmpty() || target == null) { cb.done(text == null ? "" : text); return; }
        Request request = new Request(text, target, cb);
        request.advance(() -> {
            if (!mlKitReady) { MlKit.initialize(InfoTranslator.context()); mlKitReady = true; }
            String cached = CACHE.get(request.key);
            if (cached != null) { request.finish(cached); return; }
            pending.add(request);
            request.identifier = LanguageIdentification.getClient();
            request.identifier.identifyLanguage(text)
                    .addOnSuccessListener(code -> request.advance(() -> request.identify(code)))
                    .addOnFailureListener(error -> request.finish(text));
        });
    }
    private static final class Request {
        final long generation = ExtraPrivacySession.generation();
        final String text, target, key;
        final InfoTranslator.Callback callback;
        LanguageIdentifier identifier;
        Translator translator;
        boolean done;
        Request(String text, String target, InfoTranslator.Callback callback) {
            this.text = text; this.target = target; this.callback = callback;
            key = target + "\u0000" + text;
        }
        void advance(Runnable action) {
            boolean current = ExtraPrivacySession.run(generation, () -> {
                if (done) return;
                if (!allowed()) { finish(text); return; }
                try { action.run(); } catch (Exception unavailable) { finish(text); }
            });
            if (!current) finish(text);
        }
        void identify(String code) {
            String source = TranslateLanguage.fromLanguageTag(code);
            String dest = TranslateLanguage.fromLanguageTag(target);
            if (source == null || dest == null || source.equals(dest) || "und".equals(code)) { finish(text); return; }
            translator = Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(dest).build());
            translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                    .addOnSuccessListener(ignored -> advance(() -> translator.translate(text)
                            .addOnSuccessListener(out -> advance(() -> {
                                String result = out == null || out.trim().isEmpty() ? text : out;
                                CACHE.put(key, result);
                                finish(result);
                            }))
                            .addOnFailureListener(error -> finish(text))))
                    .addOnFailureListener(error -> finish(text));
        }
        void finish(String result) {
            synchronized (ExtraPrivacySession.class) {
                if (done) return;
                done = true;
                pending.remove(this);
                if (translator != null) try { translator.close(); } catch (Exception ignored) {}
                if (identifier != null) try { identifier.close(); } catch (Exception ignored) {}
                translator = null; identifier = null;
                callback.done(generation == ExtraPrivacySession.generation() && allowed() ? result : text);
            }
        }
    }
}
