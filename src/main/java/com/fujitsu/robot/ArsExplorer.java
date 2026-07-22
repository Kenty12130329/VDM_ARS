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

    public ArsExplorer(String jarPath, String vdmPath, String transitionCsvPath) throws Exception {
        this.controller = new ArsVDMController(jarPath, vdmPath);
        this.targetOperations = loadOperationsFromCsv(transitionCsvPath);
        
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
     * ランダム探索を実行する。
     * 
     * @param maxDepth 各エピソード（パス）での最大深さ
     * @param maxRuns  最大試行（エピソード）回数
     */
    public void explore(int maxDepth, int maxRuns) {
        long startTime = System.currentTimeMillis();
        Random rand = new Random();

        System.out.println("[ARS] Starting Random Search... Max Runs: " + maxRuns + ", Max Depth: " + maxDepth);

        // ログファイルのクリア
        clearLogFiles();

        for (int run = 1; run <= maxRuns; run++) {
            if (violationDetected) {
                break;
            }

            System.out.println("[ARS] Starting Run " + run + "...");
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

                // 変数履歴の記録
                recordVariableHistory(step, run, variables);

                // 2. 不変条件（Invariant）のチェック
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
                            // 6. 実行
                            controller.executeCommand(command);
                            runPath.add(command);
                            transitionLog.add(String.format("Run %d, Step %d: Executed %s", run, step, command));
                            actionTaken = true;
                            System.out.println("  Executed: " + command);
                            break; // 実行に成功したら次のステップへ進む
                        }
                    } catch (Exception e) {
                        // エラーが発生した場合も、事前条件不適合とみなすか無視して他を試す
                        System.out.println("  Failed check/execution for: " + command + " (" + e.getMessage() + ")");
                    }
                }

                if (!actionTaken) {
                    // すべての操作が実行不可能（デッドエンド）
                    System.out.println("  [Dead End] No operations can be executed. Ending run " + run);
                    transitionLog.add(String.format("Run %d, Step %d: Dead End", run, step));
                    break;
                }
            }
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        exportStatistics(elapsedTime, maxDepth);
        exportTransitionLog();
        exportVariableHistory();

        System.out.println("[ARS] Search finished. Total steps: " + totalSteps);
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
                System.out.println("  [Debug] student_login_status: " + studentLoginStatus);
                
                // ネストされた括弧を考慮して mk_student(...) を正しく切り出す
                List<String> students = new ArrayList<>();
                int idx = 0;
                while ((idx = studentLoginStatus.indexOf("mk_student(", idx)) != -1) {
                    int start = idx;
                    int parenDepth = 1;
                    int end = -1;
                    for (int i = idx + 11; i < studentLoginStatus.length(); i++) {
                        char c = studentLoginStatus.charAt(i);
                        if (c == '(') parenDepth++;
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
                    System.out.println("    [Debug] parsed student record: " + studentStr);
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
                        violationMessage = "Student has borrowed " + borrowingCount + " books (Limit: 1). Student record: " + studentStr;
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
                            isDuplicate = pinv.custId.equals(qinv.custId) && pinv.dueDateOrCompCode.equals(qinv.dueDateOrCompCode);
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
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("mk_invoice\\(\\s*(\\d+)\\s*,\\s*(?:(\\d+)\\s*,\\s*)?(\\d+)\\s*\\)");
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
            if ("pay".equals(opName)) {
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

    private void exportStatistics(long elapsedTimeMs, int maxDepth) {
        try (PrintWriter pw = new PrintWriter(new FileWriter("statistics_summary.txt"))) {
            pw.println("=== Random Search Exploration Statistics ===");
            pw.println("Exploration Time: " + elapsedTimeMs + " ms");
            pw.println("Total Steps Executed: " + totalSteps);
            pw.println("Max Depth Limit: " + maxDepth);
            pw.println("Violation Detected: " + violationDetected);
            if (violationDetected) {
                pw.println("Violation Details: " + violationMessage);
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
        if (variableHistory.isEmpty()) return;
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
