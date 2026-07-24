package com.fujitsu.robot;

import com.fujitsu.vdmj.values.Value;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.File;
import java.util.*;

/**
 * ソルバーを一切使用せず、VDMJインタプリタを直接制御してランダム探索を行うクラス。
 * 各ステップで状態変数を取得し、モデルに合わせた引数生成と不変条件チェックをJava側で行う。
 */
public class ArsExplorer implements AutoCloseable {

    private final VDMController controller;
    private final List<String> targetOperations;
    private final String modelName;

    // ログ記録用のデータ
    private final List<String> transitionLog = new ArrayList<>();
    private final List<Map<String, String>> variableHistory = new ArrayList<>();
    private int totalSteps = 0;
    private boolean violationDetected = false;
    private String violationMessage = "";
    private boolean convergenceReached = false;

    public ArsExplorer(String jarPath, String vdmPath, String transitionCsvPath) throws Exception {
        this.controller = new ArsVDMController(jarPath, vdmPath);
        this.targetOperations = loadOperations(vdmPath, transitionCsvPath);

        // モデル名の判定（book または convenipayment）
        String lowerVdm = vdmPath.toLowerCase();
        if (lowerVdm.contains("book")) {
            this.modelName = "book";
        } else if (lowerVdm.contains("convenipayment")) {
            this.modelName = "convenipayment";
        } else {
            this.modelName = "unknown";
        }

        System.out.println("[ARS] Loaded " + targetOperations.size() + " operations: " + targetOperations);
        System.out.println("[ARS] Target model: " + modelName);
    }

    private List<String> loadOperations(String vdmPath, String csvPath) {
        if (csvPath != null && new File(csvPath).exists()) {
            List<String> ops = loadOperationsFromCsv(csvPath);
            if (!ops.isEmpty()) {
                System.out.println("[ARS] Operations loaded from CSV: " + csvPath);
                return ops;
            }
        }

        System.out.println("[ARS] CSV not found or empty. Extracting operations directly from " + vdmPath + "...");
        List<String> extracted = extractOperationsFromVdmpp(vdmPath);
        System.out.println("[ARS] Dynamically extracted operations: " + extracted);
        return extracted;
    }

    private List<String> extractOperationsFromVdmpp(String vdmPath) {
        List<String> ops = new ArrayList<>();
        try {
            String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(vdmPath)), "UTF-8");
            int opIdx = content.indexOf("operations");
            if (opIdx != -1) {
                String opSection = content.substring(opIdx);
                int endIdx = -1;
                for (String sec : new String[] { "functions", "values", "types", "end " }) {
                    int idx = opSection.indexOf(sec);
                    if (idx != -1 && (endIdx == -1 || idx < endIdx)) {
                        endIdx = idx;
                    }
                }
                if (endIdx != -1) {
                    opSection = opSection.substring(0, endIdx);
                }

                java.util.regex.Pattern p = java.util.regex.Pattern.compile("public\\s+([A-Za-z0-9_]+)\\s*:");
                java.util.regex.Matcher m = p.matcher(opSection);
                while (m.find()) {
                    String name = m.group(1);
                    if (!ops.contains(name)) {
                        ops.add(name);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ARS] Failed to extract operations from VDM-PP: " + e.getMessage());
        }
        return ops;
    }

    /**
     * transition.csvから操作名を読み込む。
     */
    private List<String> loadOperationsFromCsv(String csvPath) {
        List<String> ops = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            String line;
            boolean isHeader = true;
            while ((line = br.readLine()) != null) {
                if (isHeader) {
                    isHeader = false;
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length > 0 && !parts[0].trim().isEmpty()) {
                    ops.add(parts[0].trim());
                }
            }
        } catch (IOException e) {
            System.err.println("[ARS] Failed to load transition CSV: " + e.getMessage());
        }
        return ops;
    }

    /**
     * ランダム探索を実行する（デフォルトの収束閾値 200 ステップを使用）。
     * 
     * @param maxDepth 各パスでの最大深さ
     * @param maxRuns  最大試行回数
     */
    public void explore(int maxDepth, int maxRuns) {
        explore(maxDepth, maxRuns, 200);
    }

    /**
     * 収束判定閾値を指定してランダム探索を実行する。
     *
     * @param maxDepth          各パスでの最大深さ
     * @param maxRuns           最大試行回数
     * @param stagnantThreshold 収束判定とする連続未増加ステップ数
     */
    public void explore(int maxDepth, int maxRuns, int stagnantThreshold) {
        long startTime = System.currentTimeMillis();
        Random rand = new Random();
        ConvergenceChecker convergenceChecker = new ConvergenceChecker(stagnantThreshold);

        System.out.println("[ARS] Starting Random Search... Max Runs: " + maxRuns + ", Max Depth: " + maxDepth + ", Stagnant Threshold: " + stagnantThreshold);

        // ログファイルのクリア
        clearLogFiles();

        String prevMarking = "-";
        String prevOp = "-";

        for (int run = 1; run <= maxRuns; run++) {
            if (violationDetected || convergenceReached) {
                break;
            }

            // System.out.println("[ARS] Starting Run " + run + "...");
            try {
                // VDMJプロセスの（再）初期化
                controller.init();
            } catch (Exception e) {
                System.err.println("[ARS] Failed to initialize VDMJ: " + e.getMessage());
                e.printStackTrace();
                break;
            }

            List<String> runPath = new ArrayList<>();
            runPath.add("[Initial State]");

            for (int step = 1; step <= maxDepth; step++) {
                totalSteps++;

                // 1. 変数状態の取得
                Map<String, Value> variables;
                try {
                    variables = controller.getVariables();
                } catch (Exception e) {
                    System.err.println("[ARS] Failed to get variables: " + e.getMessage());
                    break;
                }

                String currentMarking = formatMarking(variables);

                // 変数履歴の記録
                recordVariableHistory(step, run, variables);

                // 2. 不変条件（Invariant）のチェック（最優先の即時停止）
                if (checkInvariants(variables)) {
                    violationDetected = true;
                    System.out.println("[ARS] Invariant violation detected at run " + run + ", step " + step + "!");
                    System.out.println("[ARS] Reason: " + violationMessage);

                    // トレース出力
                    saveFoundTrace(runPath, variables);
                    break;
                }

                // 3. ランダムな操作を選択して実行を試みる
                boolean actionTaken = false;
                List<String> shuffledOps = new ArrayList<>(targetOperations);
                Collections.shuffle(shuffledOps, rand);

                for (String opName : shuffledOps) {
                    // 4. 引数のランダム生成
                    String args = generateRandomArgs(opName, variables, rand);
                    String command = opName + (args.isEmpty() ? "()" : "(" + args + ")");

                    // 5. 事前条件チェック
                    try {
                        boolean isPreOK = controller.checkPrecondition(command);
                        if (isPreOK) {
                            // チェック対象（Check Object）の構築と収束判定
                            CheckObject currentObj = new CheckObject(prevMarking, prevOp, currentMarking, opName);
                            convergenceChecker.registerCheckObject(currentObj);

                            if (convergenceChecker.isConverged()) {
                                convergenceReached = true;
                                System.out.println("[ARS] Convergence criterion met at run " + run + ", step " + step + ".");
                                System.out.println("[ARS] No new Check Objects discovered in consecutive " + stagnantThreshold + " steps.");
                                break;
                            }

                            // 6. 実行
                            controller.executeCommand(command);
                            runPath.add(command);
                            transitionLog.add(String.format("Run %d, Step %d: Executed %s", run, step, command));
                            actionTaken = true;

                            prevMarking = currentMarking;
                            prevOp = opName;
                            // System.out.println("  Executed: " + command);
                            // System.out.println(String.format("[ARS] Run %d Step %d | Visited Check Objects: %d | Stagnant: %d / %d", run, step, convergenceChecker.getVisitedCount(), convergenceChecker.getStagnantSteps(), stagnantThreshold));
                            break; // 実行に成功したら次のステップへ進む
                        }
                    } catch (Exception e) {
                        // System.out.println("  Failed check/execution for: " + command + " (" + e.getMessage() + ")");
                    }
                }

                if (convergenceReached) {
                    break;
                }

                if (!actionTaken) {
                    // すべての操作が実行不可能（デッドエンド）
                    // System.out.println("  [Dead End] No operations can be executed. Ending run " + run);
                    transitionLog.add(String.format("Run %d, Step %d: Dead End", run, step));
                    break;
                }
            }
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        exportStatistics(elapsedTime, maxDepth, convergenceChecker);
        exportTransitionLog();
        exportVariableHistory();

        System.out.println("[ARS] Search finished in " + elapsedTime + " ms.");
        System.out.println("[ARS] Total Steps Executed: " + totalSteps);
        System.out.println("[ARS] Visited Unique Check Objects: " + convergenceChecker.getVisitedCount());
        System.out.println("[ARS] Violation Detected: " + violationDetected);
        System.out.println("[ARS] Convergence Reached: " + convergenceReached);
    }

    /**
     * 不変条件の評価
     */
    private boolean checkInvariants(Map<String, Value> variables) {
        if ("book".equals(modelName)) {
            // inv forall s in set student_login_status & card(s.borrowing_books) <= 1;
            Value studentLoginStatusVal = variables.get("student_login_status");
            if (studentLoginStatusVal != null) {
                String studentLoginStatus = studentLoginStatusVal.toString();
                // System.out.println("  [Debug] student_login_status: " + studentLoginStatus);

                // ネストされた括弧を考慮して mk_student(...) を正しく切り出す
                List<String> students = new ArrayList<>();
                int idx = 0;
                while ((idx = studentLoginStatus.indexOf("mk_student(", idx)) != -1) {
                    int start = idx;
                    int parenDepth = 1;
                    int end = -1;
                    for (int i = idx + 11; i < studentLoginStatus.length(); i++) {
                        char c = studentLoginStatus.charAt(i);
                        if (c == '(')
                            parenDepth++;
                        else if (c == ')') {
                            parenDepth--;
                            if (parenDepth == 0) {
                                end = i + 1;
                                break;
                            }
                        }
                    }
                    if (end != -1) {
                        students.add(studentLoginStatus.substring(start, end));
                        idx = end;
                    } else {
                        break;
                    }
                }

                for (String studentStr : students) {
                    // System.out.println("    [Debug] parsed student record: " + studentStr);
                    // studentStr 内の mk_token の出現回数をカウント
                    int tokenCount = 0;
                    int tokenIdx = 0;
                    while ((tokenIdx = studentStr.indexOf("mk_token", tokenIdx)) != -1) {
                        tokenCount++;
                        tokenIdx += 8;
                    }
                    // human_name に mk_token が使われているのでそれを除いた分が borrowing_books。
                    // 従って、card(borrowing_books) = tokenCount - 1
                    int borrowingCount = tokenCount - 1;
                    System.out.println("    [Debug] borrowingCount: " + borrowingCount);
                    if (borrowingCount > 1) {
                        violationMessage = "Student has borrowed " + borrowingCount
                                + " books (Limit: 1). Student record: " + studentStr;
                        return true;
                    }
                }
            }
        } else if ("convenipayment".equals(modelName)) {
            // pending_invoices と paid_invoices の重複チェック
            Value pendingVal = variables.get("pending_invoices");
            Value paidVal = variables.get("paid_invoices");
            if (pendingVal != null && paidVal != null) {
                String pendingStr = pendingVal.toString();
                String paidStr = paidVal.toString();

                List<Invoice> pendingList = parseInvoices(pendingStr);
                List<Invoice> paidList = parseInvoices(paidStr);

                for (Invoice pinv : pendingList) {
                    for (Invoice qinv : paidList) {
                        // 重複チェック
                        boolean isDuplicate = false;
                        if (pinv.dueDateOrCompCode == null) {
                            // ConveniPayment.vdmpp (2引数) の場合: IDのみで比較
                            isDuplicate = pinv.custId.equals(qinv.custId);
                        } else {
                            // ConveniPayment44 (3引数) の場合: company_code と customer_id で比較
                            isDuplicate = pinv.custId.equals(qinv.custId)
                                    && pinv.dueDateOrCompCode.equals(qinv.dueDateOrCompCode);
                        }
                        if (isDuplicate) {
                            violationMessage = "Duplicate invoice found in both pending and paid: ID=" + pinv.custId;
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static class Invoice {
        String custId;
        String dueDateOrCompCode;

        Invoice(String c, String d) {
            this.custId = c;
            this.dueDateOrCompCode = d;
        }
    }

    private List<Invoice> parseInvoices(String str) {
        List<Invoice> list = new ArrayList<>();
        // 2つまたは3つの引数を持つ mk_invoice を探す
        // 例: mk_invoice(123456, 3000)
        // 例: mk_invoice(123456, 100001, 3000)
        java.util.regex.Pattern p = java.util.regex.Pattern
                .compile("mk_invoice\\(\\s*(\\d+)\\s*,\\s*(?:(\\d+)\\s*,\\s*)?(\\d+)\\s*\\)");
        java.util.regex.Matcher m = p.matcher(str);
        while (m.find()) {
            if (m.group(2) != null) {
                // 3つの引数の場合
                list.add(new Invoice(m.group(1), m.group(2)));
            } else {
                // 2つの引数の場合
                list.add(new Invoice(m.group(1), null));
            }
        }
        return list;
    }

    /**
     * ランダムな引数を生成する
     */
    private String generateRandomArgs(String opName, Map<String, Value> variables, Random rand) {
        if ("book".equals(modelName)) {
            if ("borrow_book".equals(opName)) {
                // borrowable_books からランダムに選択
                Value borrowableBooksVal = variables.get("borrowable_books");
                if (borrowableBooksVal != null) {
                    String borrowable = borrowableBooksVal.toString();
                    List<String> tokens = new ArrayList<>();
                    java.util.regex.Pattern p = java.util.regex.Pattern.compile("mk_token\\(\"[^\"]+\"\\)");
                    java.util.regex.Matcher m = p.matcher(borrowable);
                    while (m.find()) {
                        tokens.add(m.group());
                    }
                    if (!tokens.isEmpty()) {
                        return tokens.get(rand.nextInt(tokens.size()));
                    }
                }
                return "mk_token(\"book_A\")";
            } else if ("return_book".equals(opName)) {
                // borrowed_books からランダムに選択
                Value borrowedBooksVal = variables.get("borrowed_books");
                if (borrowedBooksVal != null) {
                    String borrowed = borrowedBooksVal.toString();
                    List<String> tokens = new ArrayList<>();
                    java.util.regex.Pattern p = java.util.regex.Pattern.compile("mk_token\\(\"[^\"]+\"\\)");
                    java.util.regex.Matcher m = p.matcher(borrowed);
                    while (m.find()) {
                        tokens.add(m.group());
                    }
                    if (!tokens.isEmpty()) {
                        return tokens.get(rand.nextInt(tokens.size()));
                    }
                }
                return "mk_token(\"book_A\")";
            }
        } else if ("convenipayment".equals(modelName)) {
            if ("Scan".equals(opName)) {
                // ConveniPayment44_ext の Scan(comp_code, cust_id, amt, check_digit)
                long compCode = rand.nextInt(1000000);
                long custId = rand.nextInt(1000000);
                long amt = rand.nextInt(20000);
                long checkDigit = rand.nextInt(10);
                return String.format("%d, %d, %d, %d", compCode, custId, amt, checkDigit);
            } else if ("pay".equals(opName)) {
                // ConveniPayment.vdmpp (3つの引数: target_id, target_amount, check_digit)
                long targetId = rand.nextInt(1000000);
                long targetAmount = rand.nextInt(20000);
                long checkDigit = rand.nextInt(10);
                return String.format("%d, %d, %d", targetId, targetAmount, checkDigit);
            } else if ("Pay".equals(opName)) {
                Value currentSessionVal = variables.get("current_session");
                if (currentSessionVal != null) {
                    String sessionStr = currentSessionVal.toString();
                    java.util.regex.Pattern p = java.util.regex.Pattern.compile("mk_Session\\(([^\\)]+)\\)");
                    java.util.regex.Matcher m = p.matcher(sessionStr);
                    if (m.find()) {
                        String[] parts = m.group(1).split(",\\s*");
                        String amtStr = null;
                        if (parts.length == 7) {
                            amtStr = parts[4]; // ConveniPayment44
                        } else if (parts.length == 6) {
                            amtStr = parts[3]; // ConveniPayment
                        }
                        if (amtStr != null) {
                            try {
                                long amount = Long.parseLong(amtStr.trim());
                                if (amount > 0) {
                                    if (rand.nextBoolean()) {
                                        return String.valueOf(amount);
                                    } else {
                                        return String.valueOf(amount + 1000);
                                    }
                                }
                            } catch (NumberFormatException e) {
                                // Ignore
                            }
                        }
                    }
                }
                return "3000";
            }
        }
        return "";
    }

    private void clearLogFiles() {
        try {
            new FileWriter("found_trace.txt", false).close();
            new FileWriter("transition_log.csv", false).close();
            new FileWriter("variable_history.csv", false).close();
            new FileWriter("statistics_summary.txt", false).close();
        } catch (IOException e) {
            System.err.println("[ARS] Failed to clear log files: " + e.getMessage());
        }
    }

    private void recordVariableHistory(int step, int run, Map<String, Value> variables) {
        Map<String, String> historyEntry = new LinkedHashMap<>();
        historyEntry.put("Run", String.valueOf(run));
        historyEntry.put("Step", String.valueOf(step));
        for (Map.Entry<String, Value> entry : variables.entrySet()) {
            historyEntry.put(entry.getKey(), entry.getValue().toString());
        }
        variableHistory.add(historyEntry);
    }

    private void saveFoundTrace(List<String> path, Map<String, Value> finalState) {
        try (PrintWriter pw = new PrintWriter(new FileWriter("found_trace.txt", true))) {
            pw.println("=== Invariant Violation Trace ===");
            pw.println("Reason: " + violationMessage);
            pw.println("Path taken:");
            for (int i = 0; i < path.size(); i++) {
                pw.println("  Step " + i + ": " + path.get(i));
            }
            pw.println("Final State variables:");
            for (Map.Entry<String, Value> entry : finalState.entrySet()) {
                pw.println("  " + entry.getKey() + " = " + entry.getValue());
            }
            pw.println("=================================");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 変数状態のマップからマスキングされたマーキング文字列（トークン数・状態件数）を生成する。
     */
    private String formatMarking(Map<String, Value> variables) {
        if (variables == null || variables.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Value> entry : variables.entrySet()) {
            if (sb.length() > 0) sb.append(";");
            String valStr = entry.getValue() != null ? entry.getValue().toString() : "";
            int count = countElements(valStr);
            sb.append(entry.getKey()).append("=#").append(count);
        }
        return sb.toString();
    }

    /**
     * 変数値文字列から集合・構造体のトークン数（要素数）を算出する。
     */
    private int countElements(String valStr) {
        if (valStr == null || valStr.trim().isEmpty() || valStr.equals("{}") || valStr.equals("[]")) {
            return 0;
        }
        String trimmed = valStr.trim();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            String inner = trimmed.substring(1, trimmed.length() - 1).trim();
            if (inner.isEmpty()) {
                return 0;
            }
            int depth = 0;
            int count = 1;
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '{' || c == '(' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ')' || c == ']') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    count++;
                }
            }
            return count;
        }
        return 1;
    }

    private void exportStatistics(long elapsedTimeMs, int maxDepth, ConvergenceChecker convergenceChecker) {
        try (PrintWriter pw = new PrintWriter(new FileWriter("statistics_summary.txt"))) {
            pw.println("=== Random Search Exploration Statistics ===");
            pw.println("Exploration Time: " + elapsedTimeMs + " ms");
            pw.println("Total Steps Executed: " + totalSteps);
            pw.println("Max Depth Limit: " + maxDepth);
            pw.println("Violation Detected: " + violationDetected);
            if (violationDetected) {
                pw.println("Violation Details: " + violationMessage);
            }
            pw.println("Convergence Reached: " + convergenceReached);
            if (convergenceChecker != null) {
                pw.println("Visited Unique Check Objects: " + convergenceChecker.getVisitedCount());
                pw.println("Stagnant Step Threshold: " + convergenceChecker.getStagnantThreshold());
                pw.println("Consecutive Stagnant Steps: " + convergenceChecker.getStagnantSteps());
            }
            pw.println("=============================================");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void exportTransitionLog() {
        try (PrintWriter pw = new PrintWriter(new FileWriter("transition_log.csv"))) {
            pw.println("log");
            for (String log : transitionLog) {
                pw.println(log);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void exportVariableHistory() {
        if (variableHistory.isEmpty())
            return;
        try (PrintWriter pw = new PrintWriter(new FileWriter("variable_history.csv"))) {
            // ヘッダーの出力
            Map<String, String> firstEntry = variableHistory.get(0);
            pw.println(String.join(",", firstEntry.keySet()));

            // データの出力
            for (Map<String, String> entry : variableHistory) {
                List<String> values = new ArrayList<>();
                for (String val : entry.values()) {
                    // CSVのエスケープ（カンマや改行を含む場合）
                    if (val.contains(",") || val.contains("\n") || val.contains("\"")) {
                        val = "\"" + val.replace("\"", "\"\"") + "\"";
                    }
                    values.add(val);
                }
                pw.println(String.join(",", values));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void close() {
        if (controller instanceof AutoCloseable) {
            try {
                ((AutoCloseable) controller).close();
            } catch (Exception e) {
                // Ignore
            }
        }
    }
}
