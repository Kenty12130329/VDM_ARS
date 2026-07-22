package com.fujitsu.robot;

import com.fujitsu.vdmj.values.Value;
import com.fujitsu.vdmj.values.visitors.ValueVisitor;

/**
 * A simple wrapper to hold raw VDM strings (like Sets) that we don't parse
 * deeply.
 * Extracted from VDMJControllerImpl.RawValue.
 */
public class StringValue extends Value {
    private static final long serialVersionUID = 1L;
    private final String raw;

    public StringValue(String raw) {
        this.raw = raw;
    }

    @Override
    public String toString() {
        return raw;
    }

    @Override
    public String kind() {
        return "RAW";
    }

    @Override
    public Value clone() {
        return new StringValue(raw);
    }

    @Override
    public <R, S> R apply(ValueVisitor<R, S> visitor, S ctxt) {
        // Not supported, but return null to satisfy contract
        return null;
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof StringValue) {
            return raw.equals(((StringValue) other).raw);
        }
        return false;
    }
}
