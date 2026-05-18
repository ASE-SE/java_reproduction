#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_SCANNER="$ROOT/java-scanner"

MY_JSON="${MY_JSON:-$ROOT/data/deepseek-v4-flash_output.json}"
SEEKER_JSON="${SEEKER_JSON:-$ROOT/baselines/seeker/seeker_deepseek-v4-flash_output.json}"
OUT_JSON="${OUT_JSON:-$ROOT/data/static_prune_audit.json}"
SCOPE="${SCOPE:-my-fn-seeker-tp}"
LIMIT="${LIMIT:-0}"

TMP_DIR="${TMPDIR:-/tmp}/static-prune-audit.$$"
mkdir -p "$TMP_DIR"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

if ! command -v java >/dev/null 2>&1 || ! command -v javac >/dev/null 2>&1; then
  JDK17_HOME="${JDK17_HOME:-$HOME/tools/jdk-17.0.18+8}"
  if [[ -x "$JDK17_HOME/bin/java" && -x "$JDK17_HOME/bin/javac" ]]; then
    export JAVA_HOME="$JDK17_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
fi

if ! command -v java >/dev/null 2>&1; then
  echo "error: java not found in PATH. Run this script in the environment used to run java-scanner." >&2
  exit 127
fi

if ! command -v javac >/dev/null 2>&1; then
  echo "error: javac not found in PATH. This script compiles a temporary audit class under /tmp." >&2
  exit 127
fi

SRC="$TMP_DIR/StaticPruneAudit.java"
cat > "$SRC" <<'JAVA'
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.ExperimentExecutor.ProjectHandler;
import org.ExperimentExecutor.RepoHandler;
import org.callTreeGenerator.ExceptionCharacteristicManager;
import org.callTreeGenerator.GraphNodeTraversal;
import org.callTreeGenerator.MethodCallEdge;
import org.callTreeGenerator.MethodTreeNode;
import org.callTreeGenerator.TreeGenerator;
import org.callTreeGenerator.UncaughtExceptionInfo;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.jdkAnalyzer.ProjectParser;
import org.jgrapht.graph.DefaultDirectedGraph;

import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class StaticPruneAudit {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        String myPath = opts.get("--my");
        String seekerPath = opts.get("--seeker");
        String outPath = opts.get("--out");
        String scope = opts.getOrDefault("--scope", "my-fn-seeker-tp");
        int limit = Integer.parseInt(opts.getOrDefault("--limit", "0"));

        JsonArray my = loadArray(myPath);
        Map<String, JsonObject> seekerByKey = new HashMap<>();
        for (JsonElement e : loadArray(seekerPath)) {
            JsonObject o = e.getAsJsonObject();
            seekerByKey.put(key(o), o);
        }

        List<JsonObject> selected = new ArrayList<>();
        for (JsonElement e : my) {
            JsonObject o = e.getAsJsonObject();
            JsonObject s = seekerByKey.get(key(o));
            boolean label = intValue(o, "label", -1) == 1;
            boolean myChanged = intValue(o, "changed", 0) > 0;
            boolean seekerChanged = s != null && intValue(s, "changed", 0) > 0;
            if (!label || myChanged) {
                continue;
            }
            if ("my-fn-seeker-tp".equals(scope) && !seekerChanged) {
                continue;
            }
            if (!"my-fn".equals(scope) && !"my-fn-seeker-tp".equals(scope)) {
                throw new IllegalArgumentException("unsupported --scope: " + scope);
            }
            selected.add(o);
            if (limit > 0 && selected.size() >= limit) {
                break;
            }
        }

        JsonObject output = new JsonObject();
        output.addProperty("scope", scope);
        output.addProperty("my_json", myPath);
        output.addProperty("seeker_json", seekerPath);
        output.addProperty("selected_count", selected.size());
        JsonArray details = new JsonArray();

        int processed = 0;
        int failed = 0;
        int triggered = 0;
        int trueTypeRemoved = 0;
        int totalBefore = 0;
        int totalAfter = 0;
        int totalRemoved = 0;
        Map<Integer, Integer> removedDepthHistogram = new TreeMap<>();
        Map<String, Integer> removedExceptionHistogram = new TreeMap<>();

        String lastRepoCommit = "";
        for (JsonObject sample : selected) {
            JsonObject detail = baseDetail(sample, seekerByKey.get(key(sample)));
            try {
                String repoId = stringValue(sample, "repo_id");
                String commit = RepoHandler.getCommitHashFromPatch(stringValue(sample, "patch"));
                String repoCommit = repoId + "@" + commit;
                if (!repoCommit.equals(lastRepoCommit)) {
                    ProjectHandler.generateCommitParentHistoryFiles(RepoHandler.getRepository(repoId), commit);
                    lastRepoCommit = repoCommit;
                }

                ProjectParser projectParser = new ProjectParser(ProjectHandler.TMP_PROJECT_PATH);
                ExceptionCharacteristicManager manager = new ExceptionCharacteristicManager();
                TreeGenerator treeGenerator = new TreeGenerator(projectParser, manager);
                IMethodBinding target = treeGenerator.getMethodBindingByFileAndName(
                        stringValue(sample, "file_path"),
                        stringValue(sample, "methodName"));
                if (target == null) {
                    throw new IllegalStateException("target method not found");
                }

                treeGenerator.methodBinding2graph(target);
                DefaultDirectedGraph<MethodTreeNode, MethodCallEdge> graph = treeGenerator.getGraph();
                MethodTreeNode root = treeGenerator.getRoot();
                GraphNodeTraversal traversal = new GraphNodeTraversal(manager);
                List<UncaughtExceptionInfo> before = traversal.getSuspiciousThrows(graph, root);
                List<UncaughtExceptionInfo> after = new ArrayList<>(before);

                int finalLayer = applyPrune(after);
                Set<UncaughtExceptionInfo> afterSet = new HashSet<>(after);
                List<UncaughtExceptionInfo> removed = new ArrayList<>();
                for (UncaughtExceptionInfo info : before) {
                    if (!afterSet.contains(info)) {
                        removed.add(info);
                    }
                }

                boolean didTrigger = before.size() > 30;
                boolean sampleTrueTypeRemoved = removedContainsTrueType(sample, removed);
                int rootChars = root.getCode() == null ? 0 : root.getCode().length();
                PromptEstimate beforePrompt = estimatePrompt(root, before);
                PromptEstimate afterPrompt = estimatePrompt(root, after);
                processed++;
                if (didTrigger) triggered++;
                if (sampleTrueTypeRemoved) trueTypeRemoved++;
                totalBefore += before.size();
                totalAfter += after.size();
                totalRemoved += removed.size();

                detail.addProperty("status", "ok");
                detail.addProperty("before_count", before.size());
                detail.addProperty("after_count", after.size());
                detail.addProperty("removed_count", removed.size());
                detail.addProperty("triggered_prune", didTrigger);
                detail.addProperty("final_layer", finalLayer);
                detail.addProperty("true_exception_removed", sampleTrueTypeRemoved);
                detail.addProperty("root_code_chars", rootChars);
                detail.addProperty("before_candidate_block_chars", beforePrompt.candidateBlockChars);
                detail.addProperty("after_candidate_block_chars", afterPrompt.candidateBlockChars);
                detail.addProperty("removed_candidate_block_chars", beforePrompt.candidateBlockChars - afterPrompt.candidateBlockChars);
                detail.addProperty("before_prompt_estimated_chars", beforePrompt.totalChars);
                detail.addProperty("after_prompt_estimated_chars", afterPrompt.totalChars);
                detail.addProperty("removed_prompt_estimated_chars", beforePrompt.totalChars - afterPrompt.totalChars);
                detail.addProperty("before_avg_candidate_chars", before.isEmpty() ? 0.0 : beforePrompt.candidateBlockChars * 1.0 / before.size());
                detail.addProperty("after_avg_candidate_chars", after.isEmpty() ? 0.0 : afterPrompt.candidateBlockChars * 1.0 / after.size());
                detail.add("removed_depth_histogram", histogramDepths(removed, removedDepthHistogram));
                detail.add("removed_exception_types", removedExceptionTypes(removed, removedExceptionHistogram));
                detail.add("removed_candidates", summarizeRemoved(removed));
            } catch (Throwable t) {
                failed++;
                detail.addProperty("status", "failed");
                detail.addProperty("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            details.add(detail);
            System.err.println("processed " + (processed + failed) + "/" + selected.size()
                    + " ok=" + processed + " failed=" + failed);
        }

        JsonObject summary = new JsonObject();
        summary.addProperty("processed", processed);
        summary.addProperty("failed", failed);
        summary.addProperty("triggered_prune", triggered);
        summary.addProperty("triggered_prune_rate", processed == 0 ? 0.0 : triggered * 1.0 / processed);
        summary.addProperty("true_exception_removed", trueTypeRemoved);
        summary.addProperty("true_exception_removed_rate", processed == 0 ? 0.0 : trueTypeRemoved * 1.0 / processed);
        summary.addProperty("total_before_candidates", totalBefore);
        summary.addProperty("total_after_candidates", totalAfter);
        summary.addProperty("total_removed_candidates", totalRemoved);
        addPromptSummary(summary, details);
        summary.add("removed_depth_histogram", mapToJson(removedDepthHistogram));
        summary.add("removed_exception_histogram", topMapToJson(removedExceptionHistogram, 50));

        output.add("summary", summary);
        output.add("details", details);
        try (FileWriter fw = new FileWriter(outPath)) {
            GSON.toJson(output, fw);
        }
        System.err.println("wrote " + outPath);
    }

    private static int applyPrune(List<UncaughtExceptionInfo> infos) {
        int layer = 10;
        while (infos.size() > 30 && layer > 3) {
            int finalLayer = layer;
            infos.removeIf(info -> info.getNodeRoute().size() - 1 > finalLayer);
            layer--;
        }
        return layer + 1;
    }

    private static class PromptEstimate {
        int candidateBlockChars;
        int totalChars;
    }

    private static PromptEstimate estimatePrompt(MethodTreeNode root, List<UncaughtExceptionInfo> infos) {
        StringBuilder candidate = new StringBuilder();
        int i = 1;
        for (UncaughtExceptionInfo info : infos) {
            candidate.append("<call ").append(i).append(">\n");
            if (info.getNodeRoute().size() > 1) {
                candidate.append("Function in snippet: ")
                        .append(info.getNodeRoute().get(1).getSimpleName())
                        .append("\n");
                candidate.append("After ")
                        .append(info.getNodeRoute().size() - 1)
                        .append(" levels of call, in function: ")
                        .append(info.getMethodTreeNode().getSimpleName())
                        .append("\n");
            } else {
                candidate.append("Function in snippet: ")
                        .append(info.getMethodTreeNode().getSimpleName())
                        .append("\n");
            }
            candidate.append("May throw runtime exceptions: ")
                    .append(joinStrings(info.getUncaughtExceptions()))
                    .append("\n");
            candidate.append("<end ").append(i).append(">\n");
            i++;
        }
        String rootCode = root.getCode() == null ? "" : root.getCode();
        PromptEstimate estimate = new PromptEstimate();
        estimate.candidateBlockChars = candidate.length();
        // Approximate fixed text from PromptTemplate plus the code fences and markers.
        // This is intentionally conservative and excludes first-round LLM descriptions,
        // which are unknown in a static-only audit.
        int fixedPromptChars = 2600;
        estimate.totalChars = fixedPromptChars
                + "<code snippet>\n```\n\n```\n<end>\n\n".length()
                + rootCode.length()
                + candidate.length();
        return estimate;
    }

    private static String joinStrings(Set<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(v);
        }
        return sb.toString();
    }

    private static void addPromptSummary(JsonObject summary, JsonArray details) {
        int ok = 0;
        int trigger = 0;
        long beforePromptTotal = 0;
        long afterPromptTotal = 0;
        long beforeCandidateTotal = 0;
        long afterCandidateTotal = 0;
        int maxBeforePrompt = 0;
        int maxAfterPrompt = 0;
        int maxBeforeCandidate = 0;
        int maxAfterCandidate = 0;
        JsonObject maxBeforePromptSample = null;
        for (JsonElement e : details) {
            JsonObject d = e.getAsJsonObject();
            if (!"ok".equals(stringValue(d, "status"))) continue;
            ok++;
            if (d.has("triggered_prune") && d.get("triggered_prune").getAsBoolean()) trigger++;
            int bp = intValue(d, "before_prompt_estimated_chars", 0);
            int ap = intValue(d, "after_prompt_estimated_chars", 0);
            int bc = intValue(d, "before_candidate_block_chars", 0);
            int ac = intValue(d, "after_candidate_block_chars", 0);
            beforePromptTotal += bp;
            afterPromptTotal += ap;
            beforeCandidateTotal += bc;
            afterCandidateTotal += ac;
            if (bp > maxBeforePrompt) {
                maxBeforePrompt = bp;
                maxBeforePromptSample = d;
            }
            maxAfterPrompt = Math.max(maxAfterPrompt, ap);
            maxBeforeCandidate = Math.max(maxBeforeCandidate, bc);
            maxAfterCandidate = Math.max(maxAfterCandidate, ac);
        }
        summary.addProperty("avg_before_prompt_estimated_chars", ok == 0 ? 0.0 : beforePromptTotal * 1.0 / ok);
        summary.addProperty("avg_after_prompt_estimated_chars", ok == 0 ? 0.0 : afterPromptTotal * 1.0 / ok);
        summary.addProperty("avg_before_candidate_block_chars", ok == 0 ? 0.0 : beforeCandidateTotal * 1.0 / ok);
        summary.addProperty("avg_after_candidate_block_chars", ok == 0 ? 0.0 : afterCandidateTotal * 1.0 / ok);
        summary.addProperty("max_before_prompt_estimated_chars", maxBeforePrompt);
        summary.addProperty("max_after_prompt_estimated_chars", maxAfterPrompt);
        summary.addProperty("max_before_candidate_block_chars", maxBeforeCandidate);
        summary.addProperty("max_after_candidate_block_chars", maxAfterCandidate);
        if (maxBeforePromptSample != null) {
            JsonObject sample = new JsonObject();
            sample.addProperty("repo_id", stringValue(maxBeforePromptSample, "repo_id"));
            sample.addProperty("methodName", stringValue(maxBeforePromptSample, "methodName"));
            sample.addProperty("before_count", intValue(maxBeforePromptSample, "before_count", 0));
            sample.addProperty("after_count", intValue(maxBeforePromptSample, "after_count", 0));
            sample.addProperty("before_prompt_estimated_chars", maxBeforePrompt);
            summary.add("max_before_prompt_sample", sample);
        }
    }

    private static JsonObject baseDetail(JsonObject sample, JsonObject seeker) {
        JsonObject o = new JsonObject();
        o.addProperty("repo_id", stringValue(sample, "repo_id"));
        o.addProperty("patch", stringValue(sample, "patch"));
        o.addProperty("file_path", stringValue(sample, "file_path"));
        o.addProperty("methodName", stringValue(sample, "methodName"));
        o.addProperty("my_changed", intValue(sample, "changed", 0));
        o.addProperty("seeker_changed", seeker == null ? 0 : intValue(seeker, "changed", 0));
        o.add("true_exception_types", sample.has("exceptionTypes") ? sample.get("exceptionTypes") : new JsonArray());
        return o;
    }

    private static boolean removedContainsTrueType(JsonObject sample, List<UncaughtExceptionInfo> removed) {
        Set<String> trueTypes = new HashSet<>();
        if (sample.has("exceptionTypes") && sample.get("exceptionTypes").isJsonArray()) {
            for (JsonElement e : sample.getAsJsonArray("exceptionTypes")) {
                String v = e.getAsString();
                trueTypes.add(v);
                int dot = v.lastIndexOf('.');
                if (dot >= 0) trueTypes.add(v.substring(dot + 1));
            }
        }
        for (UncaughtExceptionInfo info : removed) {
            for (String ex : info.getUncaughtExceptions()) {
                String simple = ex == null ? "" : ex.substring(ex.lastIndexOf('.') + 1);
                if (trueTypes.contains(ex) || trueTypes.contains(simple)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JsonObject histogramDepths(List<UncaughtExceptionInfo> removed, Map<Integer, Integer> global) {
        Map<Integer, Integer> local = new TreeMap<>();
        for (UncaughtExceptionInfo info : removed) {
            int depth = info.getNodeRoute().size() - 1;
            local.put(depth, local.getOrDefault(depth, 0) + 1);
            global.put(depth, global.getOrDefault(depth, 0) + 1);
        }
        return mapToJson(local);
    }

    private static JsonArray removedExceptionTypes(List<UncaughtExceptionInfo> removed, Map<String, Integer> global) {
        Map<String, Integer> local = new TreeMap<>();
        for (UncaughtExceptionInfo info : removed) {
            for (String ex : info.getUncaughtExceptions()) {
                local.put(ex, local.getOrDefault(ex, 0) + 1);
                global.put(ex, global.getOrDefault(ex, 0) + 1);
            }
        }
        JsonArray arr = new JsonArray();
        for (Map.Entry<String, Integer> e : local.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("exception", e.getKey());
            o.addProperty("count", e.getValue());
            arr.add(o);
        }
        return arr;
    }

    private static JsonArray summarizeRemoved(List<UncaughtExceptionInfo> removed) {
        JsonArray arr = new JsonArray();
        for (UncaughtExceptionInfo info : removed) {
            JsonObject o = new JsonObject();
            o.addProperty("depth", info.getNodeRoute().size() - 1);
            o.addProperty("kind", info.getClass().getSimpleName());
            o.addProperty("method", info.getMethodTreeNode().getQualifiedName());
            JsonArray exs = new JsonArray();
            for (String ex : info.getUncaughtExceptions()) exs.add(ex);
            o.add("exceptions", exs);
            arr.add(o);
        }
        return arr;
    }

    private static JsonObject mapToJson(Map<Integer, Integer> map) {
        JsonObject o = new JsonObject();
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            o.addProperty(String.valueOf(e.getKey()), e.getValue());
        }
        return o;
    }

    private static JsonArray topMapToJson(Map<String, Integer> map, int limit) {
        JsonArray arr = new JsonArray();
        map.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .forEach(e -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("exception", e.getKey());
                    o.addProperty("count", e.getValue());
                    arr.add(o);
                });
        return arr;
    }

    private static JsonArray loadArray(String path) throws Exception {
        try (FileReader fr = new FileReader(path)) {
            return GSON.fromJson(fr, JsonArray.class);
        }
    }

    private static String key(JsonObject o) {
        return stringValue(o, "repo_id") + "\n"
                + stringValue(o, "patch") + "\n"
                + stringValue(o, "file_path") + "\n"
                + stringValue(o, "methodName") + "\n"
                + stringValue(o, "methodBefore");
    }

    private static String stringValue(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static int intValue(JsonObject o, String key, int fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : fallback;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + args[i]);
            }
            out.put(args[i], args[i + 1]);
        }
        return out;
    }
}
JAVA

CP="$JAVA_SCANNER/target/classes"
for jar in "$JAVA_SCANNER"/target/dependency/*.jar; do
  CP="$CP:$jar"
done

javac -cp "$CP" -d "$TMP_DIR" "$SRC"

(
  cd "$JAVA_SCANNER"
  java -cp "$TMP_DIR:$CP" StaticPruneAudit \
    --my "$MY_JSON" \
    --seeker "$SEEKER_JSON" \
    --out "$OUT_JSON" \
    --scope "$SCOPE" \
    --limit "$LIMIT"
)

echo "Result: $OUT_JSON"
