package com.nenotv.player.proextras;

import com.nenotv.player.InfoTranslator;
import com.google.mlkit.common.MlKit;

import com.google.mlkit.nl.languageid.*;
import com.google.mlkit.nl.translate.*;
import com.google.mlkit.common.model.DownloadConditions;
import android.util.LruCache;

public final class ProInfoTranslator {
    private static volatile boolean MLKIT_READY=false;
    private static boolean allowed(){
        android.content.Context context=InfoTranslator.context();
        return context!=null&&com.nenotv.player.storage.ExtraPrivacyStore.allowsSdk(context);
    }
    private static synchronized boolean ensureMlKit(){
        if(!allowed())return false;
        if(MLKIT_READY)return true;
        android.content.Context context=InfoTranslator.context();
        if(context==null)return false;
        try{
            MlKit.initialize(context);
            MLKIT_READY=true;
            return true;
        }catch(Throwable ignored){
            return false;
        }
    }
    private static final LruCache<String,String> CACHE=new LruCache<>(700);
    private ProInfoTranslator() {}
    public static void translate(String text, String target, InfoTranslator.Callback cb) {
        if(!ensureMlKit()){if(cb!=null)cb.done(text==null?"":text);return;}
        if (text == null || text.trim().isEmpty() || target == null) {cb.done(text == null ? "" : text);return;}
        final String key=target+"\u0000"+text;String cached=CACHE.get(key);if(cached!=null){cb.done(cached);return;}
        LanguageIdentifier id = LanguageIdentification.getClient();
        id.identifyLanguage(text).addOnSuccessListener(code -> {
            if(!allowed()){cb.done(text);id.close();return;}
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
                    .addOnSuccessListener(v -> {
                        if(!allowed()){cb.done(text);translator.close();id.close();return;}
                        translator.translate(text)
                        .addOnSuccessListener(out -> {
                            String ready=!allowed()||out == null || out.trim().isEmpty() ? text : out;CACHE.put(key,ready);cb.done(ready);
                            translator.close();
                            id.close();
                        })
                        .addOnFailureListener(e -> {
                            cb.done(text);
                            translator.close();
                            id.close();
                        });})
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
