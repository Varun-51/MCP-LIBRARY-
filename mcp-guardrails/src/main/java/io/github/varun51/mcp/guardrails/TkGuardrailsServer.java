package io.github.varun51.mcp.guardrails;

import static io.github.varun51.mcp.core.TkSchema.mtkArray;
import static io.github.varun51.mcp.core.TkSchema.mtkField;
import static io.github.varun51.mcp.core.TkSchema.mtkObject;
import static io.github.varun51.mcp.core.TkTool.mtkResult;

import io.github.varun51.mcp.core.TkArgs;
import io.github.varun51.mcp.core.TkServerLauncher;
import io.github.varun51.mcp.core.TkTool;
import io.github.varun51.mcp.core.TkToolException;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class TkGuardrailsServer {

    static final int _TKMAX_TEXT_CHARS = 1_000_000;
    private static final Map<String, Pattern> _TKINJECTION_RULES = Map.of(
            "override_instructions", Pattern.compile("\\b(ignore|disregard|forget|override)\\b.{0,40}\\b(previous|prior|above|earlier|all|system)\\b.{0,20}\\b(instructions?|prompts?|rules|messages?)\\b", Pattern.CASE_INSENSITIVE),
            "reveal_system_prompt", Pattern.compile("\\b(reveal|show|print|repeat|output|leak)\\b.{0,30}\\b(system prompt|hidden instructions?|initial instructions?)\\b", Pattern.CASE_INSENSITIVE),
            "role_hijack", Pattern.compile("\\b(you are now|from now on you are|pretend (to be|you are)|act as)\\b.{0,40}\\b(dan|unrestricted|jailbroken|developer mode|no (rules|limits|restrictions))\\b", Pattern.CASE_INSENSITIVE),
            "chat_template_injection", Pattern.compile("<\\|im_start\\|>|<\\|im_end\\|>|\\[/?INST\\]|<<SYS>>|<\\|system\\|>|<\\|start_header_id\\|>"),
            "tool_exfiltration", Pattern.compile("\\b(send|post|upload|exfiltrate|forward)\\b.{0,40}\\b(api keys?|credentials|secrets?|passwords?|tokens?)\\b.{0,40}\\b(to|at)\\b", Pattern.CASE_INSENSITIVE));

    private TkGuardrailsServer() {
    }

    public static void main(String[] tkArgs) {
        TkServerLauncher.mtkStartStdio("mcp-guardrails", "0.1.0", mtkTools());
    }

    static List<TkTool> mtkTools() {
        Map<String, Object> tkText = mtkField("string", "Text to inspect");
        Map<String, Object> tkTypes = mtkArray("string", "Detector types to apply, default all of the category");
        return List.of(
                new TkTool("detect_pii", "Find emails, phone numbers, Luhn-valid card numbers, checksum-valid IBANs and IP addresses. Values are returned masked",
                        mtkObject(Map.of("text", tkText, "types", tkTypes), "text"),
                        tkCall -> mtkFindings(tkCall, "pii")),
                new TkTool("detect_secrets", "Find API keys, tokens, private keys, JWTs and high-entropy strings. Values are returned masked",
                        mtkObject(Map.of("text", tkText, "types", tkTypes), "text"),
                        tkCall -> mtkFindings(tkCall, "secret")),
                new TkTool("redact", "Replace detected PII and secrets with [TYPE] placeholders",
                        mtkObject(Map.of("text", tkText, "types", tkTypes), "text"),
                        TkGuardrailsServer::mtkRedact),
                new TkTool("check_injection", "Heuristic prompt-injection screening for untrusted text such as retrieved documents or tool output",
                        mtkObject(Map.of("text", tkText), "text"),
                        TkGuardrailsServer::mtkInjection),
                new TkTool("validate_output", "Validate model output JSON against a JSON Schema",
                        mtkObject(Map.of("output", mtkField("string", "Model output expected to be JSON"), "schema", mtkField("object", "JSON Schema")), "output", "schema"),
                        TkGuardrailsServer::mtkValidate));
    }

    private static Object mtkFindings(TkArgs tkCall, String tkCategory) {
        List<Map<String, Object>> tkFindings = TkDetectors.mtkFind(mtkText(tkCall), mtkTypes(tkCall, Set.of(tkCategory))).stream()
                .map(tkFinding -> mtkResult("type", tkFinding.tkType(), "start", tkFinding.tkStart(), "end", tkFinding.tkEnd(), "masked", tkFinding.tkMasked()))
                .toList();
        return mtkResult("count", tkFindings.size(), "findings", tkFindings);
    }

    private static Object mtkRedact(TkArgs tkCall) {
        String tkText = mtkText(tkCall);
        List<TkDetectors.TkFinding> tkFindings = TkDetectors.mtkFind(tkText, mtkTypes(tkCall, Set.of("pii", "secret")));
        Map<String, Long> tkCounts = tkFindings.stream().collect(Collectors.groupingBy(TkDetectors.TkFinding::tkType, TreeMap::new, Collectors.counting()));
        return mtkResult("text", TkDetectors.mtkRedact(tkText, tkFindings), "redactions", tkCounts);
    }

    private static Object mtkInjection(TkArgs tkCall) {
        String tkText = mtkText(tkCall);
        List<String> tkMatched = _TKINJECTION_RULES.entrySet().stream()
                .filter(tkRule -> tkRule.getValue().matcher(tkText).find())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        return mtkResult("flagged", !tkMatched.isEmpty(), "rules", tkMatched);
    }

    private static Object mtkValidate(TkArgs tkCall) {
        Object tkOutput;
        try {
            tkOutput = McpJsonDefaults.getMapper().readValue(mtkCapped(tkCall.mtkString("output")), Object.class);
        } catch (IOException tkError) {
            return mtkResult("valid", false, "errors", List.of("output is not valid JSON"));
        }
        ValidationResponse tkResponse = McpJsonDefaults.getSchemaValidator().validate(tkCall.mtkMap("schema"), tkOutput);
        return mtkResult("valid", tkResponse.valid(), "errors", tkResponse.valid() ? List.of() : List.of(tkResponse.errorMessage()));
    }

    private static Set<String> mtkTypes(TkArgs tkCall, Set<String> tkCategories) {
        Set<String> tkAvailable = TkDetectors._TKALL.stream().filter(tkDetector -> tkCategories.contains(tkDetector.tkCategory()))
                .map(TkDetectors.TkDetector::tkType).collect(Collectors.toSet());
        if (!tkCall.mtkHas("types")) {
            return tkAvailable;
        }
        Set<String> tkRequested = new HashSet<>(tkCall.mtkStringList("types"));
        TkToolException.mtkCheck(tkAvailable.containsAll(tkRequested), "unknown detector types " + tkRequested.stream().filter(tkType -> !tkAvailable.contains(tkType)).sorted().toList()
                + ", available: " + tkAvailable.stream().sorted().toList());
        return tkRequested;
    }

    private static String mtkText(TkArgs tkCall) {
        return mtkCapped(tkCall.mtkString("text"));
    }

    private static String mtkCapped(String tkText) {
        TkToolException.mtkCheck(tkText.length() <= _TKMAX_TEXT_CHARS, "text too long, limit is " + _TKMAX_TEXT_CHARS + " characters");
        return tkText;
    }
}
