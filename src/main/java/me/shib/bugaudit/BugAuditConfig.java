package me.shib.bugaudit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.shib.bugaudit.commons.BugAuditContent;
import me.shib.bugaudit.commons.BugAuditException;
import me.shib.bugaudit.tracker.BatComment;
import me.shib.bugaudit.tracker.BatIssue;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

final class BugAuditConfig {

    static transient final String issueFixedComment = "This issue has been fixed.";
    static transient final String issueNotFixedComment = "Found that the issue is still not fixed.";
    static transient final String resolveRequestComment = "Please resolve this issue.";
    static transient final String reopenRequestComment = "Please reopen this issue.";
    static transient final String autoResolvingNotificationComment = "Auto resolving this issue.";
    static transient final String closingNotificationComment = "Closing this issue after verification.";
    static transient final String reopeningNotificationComment = "Reopening this issue as it is not fixed.";

    private static transient final String bugauditConfigEnv = "BUGAUDIT_CONFIG";
    private static transient final String batProjectEnv = "BUGAUDIT_PROJECT";
    private static transient final String batIssueTypeEnv = "BUGAUDIT_ISSUETYPE";
    private static transient final String batAssigneeEnv = "BUGAUDIT_ASSIGNEE";
    private static transient final String batSubscribersEnv = "BUGAUDIT_SUBSCRIBERS";
    private static transient final Gson gson = new GsonBuilder().create();

    private static transient BugAuditConfig config;

    private String project;
    private String issueType;
    private boolean summaryUpdateAllowed;
    private boolean descriptionUpdateAllowed;
    private boolean labelUpdateAllowed;
    private boolean reprioritizeAllowed;
    private boolean deprioritizeAllowed;
    private Map<String, Integer> priorityMap;
    private Map<String, Object> customFields;
    private Users users;
    private HashMap<String, List<String>> transitions;
    private List<String> openStatuses;
    private List<String> resolvedStatuses;
    private List<String> closedStatuses;
    private List<String> ignorableLabels;
    private List<String> ignorableStatuses;
    private UpdateActions toOpen;
    private UpdateActions toClose;

    private static String readFromFile(File file) throws IOException {
        if (!file.exists() || file.isDirectory()) {
            return "";
        }
        StringBuilder contentBuilder = new StringBuilder();
        BufferedReader br = new BufferedReader(new FileReader(file));
        String line;
        while ((line = br.readLine()) != null) {
            contentBuilder.append(line).append("\n");
        }
        br.close();
        return contentBuilder.toString();
    }

    private static String getConfigFromURL(String remoteConfigURL) throws IOException {
        StringBuilder result = new StringBuilder();
        URL url = new URL(remoteConfigURL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String line;
        while ((line = rd.readLine()) != null) {
            result.append(line).append("\n");
        }
        rd.close();
        return result.toString();
    }

    static synchronized BugAuditConfig getConfig() throws BugAuditException, IOException {
        if (config == null) {
            String configJson = null;
            String configURI = System.getenv(bugauditConfigEnv);
            if (configURI != null && !configURI.isEmpty()) {
                if (configURI.trim().toLowerCase().startsWith("http://") ||
                        configURI.trim().toLowerCase().startsWith("https://")) {
                    configJson = getConfigFromURL(configURI);
                } else {
                    configJson = readFromFile(new File(configURI));
                }
            }
            if (configJson == null || configJson.isEmpty()) {
                throw new BugAuditException("Please provide a valid config file or URL through " +
                        bugauditConfigEnv + " environment variable.");
            }
            config = gson.fromJson(configJson, BugAuditConfig.class);
            config.validate();
        }
        return config;
    }

    private void nullValidation(Object object, String name) throws BugAuditException {
        if (object == null) {
            throw new BugAuditException(name + " is mandatory and can't be null");
        }
    }

    private void validate() throws BugAuditException {
        if (project == null || project.isEmpty()) {
            project = System.getenv(batProjectEnv);
        }
        nullValidation(project, "project");
        if (issueType == null || issueType.isEmpty()) {
            issueType = System.getenv(batIssueTypeEnv);
        }
        nullValidation(issueType, "issueType");
        nullValidation(priorityMap, "priorityMap");
        if (customFields == null) {
            customFields = new HashMap<>();
        }
        if (users == null) {
            users = new Users();
        }
        if (resolvedStatuses == null) {
            resolvedStatuses = new ArrayList<>();
        }
        if (openStatuses == null) {
            openStatuses = new ArrayList<>();
        }
        if (closedStatuses == null) {
            closedStatuses = new ArrayList<>();
        }
        if (ignorableLabels == null) {
            ignorableLabels = new ArrayList<>();
        }
        if (ignorableStatuses == null) {
            ignorableStatuses = new ArrayList<>();
        }
        if (toOpen == null) {
            toOpen = new UpdateActions(true, true, UpdateActions.defaultCommentInterval);
        }
        toOpen.validate();
        if (toClose == null) {
            toClose = new UpdateActions(true, true, UpdateActions.defaultCommentInterval);
        }
        toClose.validate();
        if (closedStatuses.size() == 0) {
            if (!toClose.commentable || !toClose.statusTransferable) {
                throw new BugAuditException("Expecting at least one valid Close statuses in config");
            }
        }
        if (resolvedStatuses.size() == 0) {
            if (!toOpen.commentable || !toOpen.statusTransferable) {
                throw new BugAuditException("Expecting at least one valid Resolved statuses in config");
            }
        }
        if (openStatuses.size() == 0) {
            if (!toOpen.commentable || !toOpen.statusTransferable) {
                throw new BugAuditException("Expecting at least one valid Open statuses in config");
            }
        }
    }

    Map<String, Integer> getPriorityMap() {
        return priorityMap;
    }

    UpdateActions toOpen() {
        return toOpen;
    }

    UpdateActions toClose() {
        return toClose;
    }

    boolean isResolvedStatus(String currentStatus) {
        for (String status : resolvedStatuses) {
            if (status.equalsIgnoreCase(currentStatus)) {
                return true;
            }
        }
        return false;
    }

    private List<String> getTransitionPath(List<String> path, String fromStatus, List<String> toStatuses) {
        if (path.contains(fromStatus)) {
            return new ArrayList<>();
        }
        path.add(fromStatus);
        if (toStatuses.contains(fromStatus)) {
            return path;
        }
        List<List<String>> temp = new ArrayList<>();
        List<String> forwardStatuses = transitions.get(fromStatus);
        if (forwardStatuses == null) {
            System.out.println("Unable to find transitions for status: " + fromStatus);
        }
        for (String status : transitions.get(fromStatus)) {
            List<String> current = getTransitionPath(new ArrayList<>(path), status, toStatuses);
            if (current.size() > 0 && toStatuses.contains(current.get(current.size() - 1))) {
                temp.add(current);
            }
        }
        int minSize = 0;
        List<String> selected = null;
        for (List<String> list : temp) {
            if ((minSize == 0) || (list.size() > 0 && list.size() < selected.size())) {
                selected = list;
                minSize = selected.size();
            }
        }
        if (selected != null && selected.size() > 1) {
            return selected;
        }
        return path;
    }

    List<String> getTransitionsToOpen(String currentStatus) {
        return getTransitionPath(new ArrayList<String>(), currentStatus, openStatuses);
    }

    List<String> getTransitionsToClose(String currentStatus) {
        return getTransitionPath(new ArrayList<String>(), currentStatus, closedStatuses);
    }

    boolean isClosingAllowed() {
        return toClose.isStatusTransferable() || toClose.isCommentable();
    }

    boolean isSummaryUpdateAllowed() {
        return this.summaryUpdateAllowed;
    }

    boolean isDescriptionUpdateAllowed() {
        return this.descriptionUpdateAllowed;
    }

    boolean isLabelUpdateAllowed() {
        return labelUpdateAllowed;
    }

    boolean isReprioritizeAllowed() {
        return this.reprioritizeAllowed;
    }

    boolean isDeprioritizeAllowed() {
        return this.deprioritizeAllowed;
    }

    boolean isOpeningAllowedForStatus(String status) {
        if (toOpen.isStatusTransferable() || toOpen.isCommentable()) {
            for (String s : resolvedStatuses) {
                if (s.equalsIgnoreCase(status)) {
                    return true;
                }
            }
            for (String s : closedStatuses) {
                if (s.equalsIgnoreCase(status)) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean isIssueIgnorable(BatIssue issue) {
        for (String status : ignorableStatuses) {
            if (status.equalsIgnoreCase(issue.getStatus())) {
                return true;
            }
        }
        for (String ignorableLabel : ignorableLabels) {
            for (String issueLabel : issue.getLabels()) {
                if (ignorableLabel.equalsIgnoreCase(issueLabel)) {
                    return true;
                }
            }
        }
        return false;
    }

    String getProject() {
        return this.project;
    }

    String getIssueType() {
        return this.issueType;
    }

    Users getUsers() {
        return users;
    }

    List<String> getClosedStatuses() {
        return this.closedStatuses;
    }

    Map<String, Object> getCustomFields() {
        return customFields;
    }

    class Users {
        private String assignee;
        private List<String> subscribers;

        private Users() {
            this.assignee = System.getenv(batAssigneeEnv);
            String subs = System.getenv(batSubscribersEnv);
            this.subscribers = new ArrayList<>();
            if (subs != null && !subs.isEmpty()) {
                this.subscribers.addAll(Arrays.asList(subs.split(",")));
            }
        }

        String getAssignee() {
            return assignee;
        }

        List<String> getSubscribers() {
            return subscribers;
        }
    }

    class UpdateActions {

        private transient static final int defaultCommentInterval = 30;
        private transient static final long oneDay = 86400000;

        private boolean statusTransferable;
        private boolean commentable;
        private int commentInterval;

        private UpdateActions(boolean statusTransferable, boolean commentable, int commentInterval) {
            this.statusTransferable = statusTransferable;
            this.commentable = commentable;
            this.commentInterval = commentInterval;
        }

        boolean isStatusTransferable() {
            return statusTransferable;
        }

        boolean isCommentable() {
            return commentable;
        }

        boolean isCommentable(BatIssue issue, BugAuditContent commentToAdd) throws BugAuditException {
            if (commentable) {
                issue.refresh();
                BatComment lastComment = null;
                for (BatComment comment : issue.getComments()) {
                    if (comment.getBody().toLowerCase().contains(commentToAdd.getMarkdownContent().toLowerCase())) {
                        lastComment = comment;
                    }
                }
                long commentBeforeTime = new Date().getTime() - commentInterval * oneDay;
                return (lastComment == null) || (lastComment.getUpdatedDate().getTime() < commentBeforeTime);
            }
            return false;
        }


        void validate() {
            if (commentInterval < 1) {
                commentInterval = defaultCommentInterval;
            }
        }
    }
}
