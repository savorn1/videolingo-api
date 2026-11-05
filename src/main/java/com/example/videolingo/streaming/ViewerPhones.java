package com.example.videolingo.streaming;

// RuengFlix viewers' phone numbers, stored by movie-api in E.164 ("+85512345678"). Pure and static.
public final class ViewerPhones {

    private ViewerPhones() {}

    /** "+85512345678" → "012 345 678", the way Cambodians write it; anything else unchanged. */
    public static String display(String e164) {
        if (e164 == null || !e164.startsWith("+855")) return e164;
        String local = "0" + e164.substring(4);
        if (local.length() < 7) return local;
        return local.substring(0, 3) + " " + local.substring(3, 6) + " " + local.substring(6);
    }

    /**
     * The digits to look for in stored numbers when an admin searches: "012 345" → "12345",
     * "+855 12" → "12". Empty when the search has fewer than 3 digits (too broad to be a phone search).
     */
    public static String searchDigits(String query) {
        String digits = query.replaceAll("\\D", "");
        if (digits.startsWith("855") && digits.length() > 3) digits = digits.substring(3);
        if (digits.startsWith("0")) digits = digits.substring(1);
        return digits.length() >= 3 ? digits : "";
    }
}
