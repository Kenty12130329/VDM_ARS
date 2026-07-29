package com.fujitsu.robot;

import java.util.HashSet;
import java.util.Set;

/**
 * 自動ランダムシミュレーションにおける終了基準（収束判定）を管理するクラス。
 * チェック対象（Check Objects）の累積到達数を記録し、新規到達の増加率が平坦化した時点で収束と判定する。
 */
public class ConvergenceChecker {

    private final Set<CheckObject> visitedObjects = new HashSet<>();
    private final int stagnantThreshold;
    private int stagnantSteps = 0;

    /**
     * 収束判定器を初期化する。
     *
     * @param stagnantThreshold 収束と判定する連続未更新ステップ数
     */
    public ConvergenceChecker(int stagnantThreshold) {
        this.stagnantThreshold = stagnantThreshold;
    }

    /**
     * チェック対象を登録し、新規到達か既知到達かを評価する。
     *
     * @param checkObject 評価対象の CheckObject インスタンス
     * @return 新規に到達したチェック対象である場合は true、既知の場合は false
     */
    public boolean registerCheckObject(CheckObject checkObject) {
        if (checkObject == null) {
            stagnantSteps++;
            return false;
        }

        if (visitedObjects.add(checkObject)) {
            stagnantSteps = 0;
            return true;
        } else {
            stagnantSteps++;
            return false;
        }
    }

    /**
     * 連続未増加ステップ数が閾値に達し、収束したか判定する。
     *
     * @return 収束基準を満たしている場合は true
     */
    public boolean isConverged() {
        return stagnantSteps >= stagnantThreshold;
    }

    /**
     * 到達したユニークなチェック対象の総数を取得する。
     *
     * @return 総到達数
     */
    public int getVisitedCount() {
        return visitedObjects.size();
    }

    /**
     * 現在の連続未増加ステップ数を取得する。
     *
     * @return 連続未増加ステップ数
     */
    public int getStagnantSteps() {
        return stagnantSteps;
    }

    /**
     * 設定されている収束閾値を取得する。
     *
     * @return 閾値ステップ数
     */
    public int getStagnantThreshold() {
        return stagnantThreshold;
    }
}
