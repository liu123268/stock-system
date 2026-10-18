package com.stock.system.agent.report;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;

import java.nio.file.*;
import java.util.*;

/** BGE uses the normalized CLS token, not the mean pooling of a generic sentence transformer. */
public final class LocalReportEmbedding implements EmbeddingModel, AutoCloseable {
    public static final String VERSION = "bge-zh-small-46fbe35-cls-ort120-djl038-v1";
    public static final String QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章：";
    private final HuggingFaceTokenizer tokenizer;
    private final OrtSession session;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();

    public LocalReportEmbedding(Path directory) throws Exception {
        Path model = directory.resolve("model_optimized.onnx");
        Path tokens = directory.resolve("tokenizer.json");
        if (!ReportCatalog.sha256(Files.readAllBytes(model)).equals("1294ea4b6331115a353d81f96b85e8c8d7fdcc284453d5b2fab5b016230aad38")
                || !ReportCatalog.sha256(Files.readAllBytes(tokens)).equals("48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26"))
            throw new IllegalArgumentException("Reviewed embedding assets changed");
        tokenizer = HuggingFaceTokenizer.newInstance(tokens, Map.of("truncation", "false", "padding", "false"));
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            options.setInterOpNumThreads(1);
            session = environment.createSession(model.toString(), options);
        } catch (Exception failure) { tokenizer.close(); throw failure; }
    }
    @Override public int dimensions() { return 512; }
    @Override public float[] embed(Document document) { return embed(document.getText()); }
    @Override public synchronized EmbeddingResponse call(EmbeddingRequest request) {
        var output = new ArrayList<Embedding>();
        for (String text : request.getInstructions()) {
            var encoding = tokenizer.encode(text);
            if (encoding.getIds().length > 512 || encoding.getIds().length < 2 || encoding.getOverflowing().length > 0)
                throw new IllegalArgumentException("Report embedding input exceeds reviewed token limit");
            try (var ids = OnnxTensor.createTensor(environment, new long[][]{encoding.getIds()});
                 var mask = OnnxTensor.createTensor(environment, new long[][]{encoding.getAttentionMask()});
                 var types = OnnxTensor.createTensor(environment, new long[][]{encoding.getTypeIds()});
                 var result = session.run(Map.of("input_ids", ids, "attention_mask", mask, "token_type_ids", types))) {
                var tensor = (float[][][]) result.get("last_hidden_state").orElseThrow().getValue();
                float[] vector = tensor[0][0].clone();
                if (vector.length != dimensions()) throw new IllegalStateException("Embedding dimension changed");
                double squared = 0;
                for (float value : vector) { if (!Float.isFinite(value)) throw new IllegalStateException("Invalid embedding"); squared += value * (double) value; }
                if (squared <= 0) throw new IllegalStateException("Empty embedding");
                for (int i = 0; i < vector.length; i++) vector[i] /= (float) Math.sqrt(squared);
                output.add(new Embedding(vector, output.size()));
            } catch (OrtException failure) { throw new IllegalStateException("Local report encoding failed", failure); }
        }
        return new EmbeddingResponse(output);
    }
    @Override public void close() throws OrtException { try { session.close(); } finally { tokenizer.close(); } }
}
