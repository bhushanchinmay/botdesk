package io.github.bhushanchinmay.botdesk.quality;

/**
 * One changed file from {@code git diff --name-status}.
 * For renames git prints "R100\told\tnew"; the path kept here is the new one.
 */
public record FileChange(char status, String path) {

    public static FileChange parse(String nameStatusLine) {
        String[] parts = nameStatusLine.split("\t");
        if (parts.length < 2 || parts[0].isEmpty()) {
            throw new IllegalArgumentException("Not a name-status line: " + nameStatusLine);
        }
        return new FileChange(parts[0].charAt(0), parts[parts.length - 1]);
    }

    public boolean isDeleted() { return status == 'D'; }

    @Override
    public String toString() { return status + "\t" + path; }
}
