package com.fujitsu.robot;

import java.util.Objects;

/**
 * チェック対象（Check Object）を表すクラス。
 * 状態（マーキング）とデータアクセス操作（書き込み・読み込み）の組み合わせを保持する。
 */
public class CheckObject {

    private final String markingW;
    private final String opW;
    private final String markingR;
    private final String opR;

    /**
     * チェック対象を初期化する。
     *
     * @param markingW 書き込み時の状態（マーキング）
     * @param opW      書き込み操作名
     * @param markingR 読み込み時の状態（マーキング）
     * @param opR      読み込み操作名
     */
    public CheckObject(String markingW, String opW, String markingR, String opR) {
        this.markingW = markingW != null ? markingW : "-";
        this.opW = opW != null ? opW : "-";
        this.markingR = markingR != null ? markingR : "-";
        this.opR = opR != null ? opR : "-";
    }

    public String getMarkingW() {
        return markingW;
    }

    public String getOpW() {
        return opW;
    }

    public String getMarkingR() {
        return markingR;
    }

    public String getOpR() {
        return opR;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CheckObject that = (CheckObject) o;
        return Objects.equals(markingW, that.markingW) &&
               Objects.equals(opW, that.opW) &&
               Objects.equals(markingR, that.markingR) &&
               Objects.equals(opR, that.opR);
    }

    @Override
    public int hashCode() {
        return Objects.hash(markingW, opW, markingR, opR);
    }

    @Override
    public String toString() {
        return String.format("(Mw=%s, w=%s, Mr=%s, r=%s)", markingW, opW, markingR, opR);
    }
}
