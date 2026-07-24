package com.fujitsu.robot;

import java.io.File;

/**
 * ランダム探索の実行を開始するメインクラス。
 * 使用方法: java com.fujitsu.robot.ArsMain <vdmpp_file> [max_depth] [max_runs] [stagnant_threshold]
 */
public class ArsMain {

    public static void main(String[] args) {
        System.out.println("==========================================");
        System.out.println("   Random Search Tool for VDM (No Z3)     ");
        System.out.println("==========================================");

        if (args.length == 0) {
            System.out.println("使用方法: java com.fujitsu.robot.ArsMain <vdmpp_file> [max_depth] [max_runs] [stagnant_threshold]");
            System.out.println("デフォルトとして resources/book.vdmpp を使用します。");
            args = new String[] { "resources/book.vdmpp" };
        }

        String vdmPath = args[0];
        int maxDepth = 10;
        int maxRuns = 1000; // 不変条件違反を見つけるための十分な試行数
        int stagnantThreshold = 200; // 収束と判定する連続未更新ステップ数

        if (args.length > 1) {
            try {
                maxDepth = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("[Warn] Ignoring unknown depth argument: " + args[1]);
            }
        }

        if (args.length > 2) {
            try {
                maxRuns = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                System.err.println("[Warn] Ignoring unknown runs argument: " + args[2]);
            }
        }

        if (args.length > 3) {
            try {
                stagnantThreshold = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                System.err.println("[Warn] Ignoring unknown stagnant threshold argument: " + args[3]);
            }
        }

        System.out.println("[Config] VDM File: " + vdmPath);
        System.out.println("[Config] Max Depth: " + maxDepth);
        System.out.println("[Config] Max Runs: " + maxRuns);
        System.out.println("[Config] Stagnant Threshold: " + stagnantThreshold);

        // ファイル存在チェック
        File f = new File(vdmPath);
        if (!f.exists()) {
            System.err.println("[Error] Missing VDM file: " + f.getAbsolutePath());
            System.exit(1);
        }

        // VDMファイル名に応じた transition.csv の設定
        String transitionCsvPath = "resources/transition.csv";
        if (vdmPath.contains("book")) {
            transitionCsvPath = "resources/transition_book.csv";
        } else if (vdmPath.contains("ConveniPayment44_bug")) {
            transitionCsvPath = "resources/transition_convenipayment44_bug.csv";
        } else if (vdmPath.contains("ConveniPayment44")) {
            transitionCsvPath = "resources/transition_convenipayment44.csv";
        }

        System.out.println("[Config] Transition CSV: " + transitionCsvPath);

        // VDMJ jar のパス（デフォルトは jar/vdmj-4.6.0.jar）
        String jarPath = "jar/vdmj-4.6.0.jar";
        if (!new File(jarPath).exists()) {
            System.err.println("[Error] Missing VDMJ jar file: " + new File(jarPath).getAbsolutePath());
            System.exit(1);
        }

        try (ArsExplorer explorer = new ArsExplorer(jarPath, vdmPath, transitionCsvPath)) {
            explorer.explore(maxDepth, maxRuns, stagnantThreshold);
        } catch (Exception e) {
            System.err.println("[Fatal Error] Exploration aborted due to an unexpected error:");
            e.printStackTrace();
        }
        System.out.println("==========================================");
    }
}
