package me.shib.bugaudit;

import me.shib.bugaudit.commons.BugAuditContent;
import me.shib.bugaudit.commons.BugAuditException;
import me.shib.bugaudit.scanner.Bug;
import me.shib.bugaudit.scanner.BugAuditScanResult;
import me.shib.bugaudit.tracker.BatIssue;
import me.shib.bugaudit.tracker.BatIssueFactory;
import me.shib.bugaudit.tracker.BatSearchQuery;
import me.shib.bugaudit.tracker.BugAuditTracker;

import java.io.IOException;
import java.util.*;

final class BugAuditWorker {

    private static final String batReadOnlyEnv = System.getenv("BUGAUDIT_READONLY");
    private static final boolean readOnly = (batReadOnlyEnv != null) && batReadOnlyEnv.equalsIgnoreCase("TRUE");

    private List<Exception> exceptions;

    private BugAuditConfig config;
    private BugAuditTracker tracker;
    private BugAuditScanResult scanResult;

    BugAuditWorker(BugAuditScanResult scanResult) throws BugAuditException, IOException {
        this.exceptions = new ArrayList<>();
        this.scanResult = scanResult;
        this.config = BugAuditConfig.getConfig();
        this.tracker = getContextTracker();
    }

    private BugAuditTracker getContextTracker() {
        List<String> projects = new ArrayList<>();
        projects.add(config.getProject());
        BatSearchQuery query = new BatSearchQuery();
        query.add(BatSearchQuery.Condition.label, BatSearchQuery.Operator.equals, scanResult.getRepo().toString());
        query.add(BatSearchQuery.Condition.label, BatSearchQuery.Operator.equals, scanResult.getLang().toString());
        query.add(BatSearchQuery.Condition.label, BatSearchQuery.Operator.equals, scanResult.getTool());
        for (String key : scanResult.getKeys()) {
            query.add(BatSearchQuery.Condition.label, BatSearchQuery.Operator.equals, key);
        }
        return BugAuditTracker.getTracker(config.getPriorityMap(), query, projects);
    }

    private BatIssue createBatIssueForBug(Bug bug) {
        Set<String> labels = new HashSet<>();
        labels.add(scanResult.getTool());
        labels.add(scanResult.getBugAuditLabel());
        labels.add(scanResult.getLang().toString());
        labels.add(scanResult.getRepo().toString());
        labels.add(scanResult.getTool());
        labels.addAll(scanResult.getKeys());
        labels.addAll(bug.getTags());
        BatIssueFactory batIssueFactory = new BatIssueFactory();
        batIssueFactory.setProject(config.getProject());
        batIssueFactory.setTitle(bug.getTitle());
        batIssueFactory.setIssueType(config.getIssueType());
        batIssueFactory.setAssignee(config.getUsers().getAssignee());
        batIssueFactory.setSubscribers(config.getUsers().getSubscribers());
        batIssueFactory.setPriority(bug.getPriority());
        batIssueFactory.setDescription(bug.getDescription());
        batIssueFactory.setLabels(new ArrayList<>(labels));
        batIssueFactory.setCustomFields(config.getCustomFields());
        BatIssue batIssue = tracker.createIssue(batIssueFactory);
        System.out.println("Created new issue: " + batIssue.getKey() + " - " + batIssue.getTitle() + " with priority "
                + batIssue.getPriority().getName());
        return batIssue;
    }

    private BatIssue updateBatIssueForBug(BatIssue batIssue, Bug bug) {
        if (config.isIssueIgnorable(batIssue)) {
            System.out.println("Ignoring the issue: " + batIssue.getKey());
            return batIssue;
        }
        boolean issueUpdated = false;
        BatIssueFactory batIssueFactory = new BatIssueFactory();
        batIssueFactory.setProject(config.getProject());
        if (config.isSummaryUpdateAllowed() && !batIssue.getTitle().contentEquals(bug.getTitle())) {
            batIssueFactory.setTitle(bug.getTitle());
            issueUpdated = true;
        }
        if (config.isDescriptionUpdateAllowed() &&
                !tracker.areContentsMatching(bug.getDescription(), batIssue.getDescription())) {
            batIssueFactory.setDescription(bug.getDescription());
            issueUpdated = true;
        }
        StringBuilder comment = new StringBuilder();
        if (((batIssue.getPriority().getValue() < bug.getPriority()) && (config.isReprioritizeAllowed()))
                || ((batIssue.getPriority().getValue() > bug.getPriority()) && (config.isDeprioritizeAllowed()))) {
            batIssueFactory.setPriority(bug.getPriority());
            System.out.println("Reprioritizing " + batIssue.getKey() + " to " + tracker.getPriorityName(bug.getPriority()) + " based on actual priority.");
            comment.append("Reprioritizing to **").append(tracker.getPriorityName(bug.getPriority())).append("** based on actual priority.");
            issueUpdated = true;
        }
        if (issueUpdated) {
            batIssue = tracker.updateIssue(batIssue, batIssueFactory);
            if (!comment.toString().isEmpty()) {
                batIssue.addComment(new BugAuditContent(comment.toString()));
            }
        }
        if (config.isOpeningAllowedForStatus(batIssue.getStatus())) {
            if (reopenIssue(batIssue)) {
            }
        } else if (issueUpdated) {
            System.out.println("Updated the issue: " + batIssue.getKey() + " - "
                    + batIssue.getTitle());
        } else {
            System.out.println("Issue up-to date: " + batIssue.getKey() + " - "
                    + batIssue.getTitle());
        }
        return batIssue;
    }

    private List<String> toLowerCase(List<String> list) {
        List<String> lowerCaseList = new ArrayList<>();
        for (String item : list) {
            lowerCaseList.add(item.toLowerCase());
        }
        return lowerCaseList;
    }

    private boolean isVulnerabilityExists(BatIssue batIssue, List<Bug> bugs) {
        for (Bug bug : bugs) {
            if (toLowerCase(batIssue.getLabels()).containsAll(toLowerCase(new ArrayList<>(bug.getKeys())))) {
                return true;
            }
        }
        return false;
    }

    private boolean closeIssue(BatIssue issue) {
        if (config.isIssueIgnorable(issue)) {
            System.out.println("Ignoring the issue: " + issue.getKey());
            return false;
        }
        System.out.println("Issue: " + issue.getKey() + " has been fixed.");
        boolean transitioned = false;
        if (config.toClose().isStatusTransferable() && config.isClosingTransitionAllowedForStatus(issue.getStatus())) {
            List<String> transitions = config.getTransitionsToClose(issue.getStatus());
            System.out.println("Closing the issue " + issue.getKey() + ".");
            transitioned = transitionIssue(transitions, issue);
            if (!transitioned) {
                System.out.println("No path defined to Close the issue from \"" + issue.getStatus() + "\" state.");
            }
        }
        StringBuilder comment = new StringBuilder();
        if (config.toClose().isCommentable(issue, new BugAuditContent(BugAuditConfig.issueFixedComment))) {
            comment.append("\n").append(BugAuditConfig.issueFixedComment);
            if (!transitioned) {
                comment.append("\n").append(BugAuditConfig.resolveRequestComment);
            }
        }
        if (transitioned) {
            if (!comment.toString().isEmpty()) {
                comment.append("\n");
            }
            comment.append(BugAuditConfig.closingNotificationComment);
        }
        if (!comment.toString().isEmpty()) {
            issue.addComment(new BugAuditContent(comment.toString()));
            return true;
        }
        return transitioned;
    }

    private boolean reopenIssue(BatIssue issue) {
        System.out.println("Issue: " + issue.getKey() + " was resolved, but not actually fixed.");
        boolean transitioned = false;
        if (config.toOpen().isStatusTransferable()) {
            List<String> transitions = config.getTransitionsToOpen(issue.getStatus());
            System.out.println("Reopening the issue " + issue.getKey() + ":");
            transitioned = transitionIssue(transitions, issue);
            if (!transitioned) {
                System.out.println("No path defined to Open the issue from \"" + issue.getStatus() + "\" state.");
            }
        }
        StringBuilder comment = new StringBuilder();
        if (config.toOpen().isCommentable(issue, new BugAuditContent(BugAuditConfig.issueNotFixedComment))) {
            comment.append(BugAuditConfig.issueNotFixedComment);
            if (!transitioned) {
                comment.append("\n").append(BugAuditConfig.reopenRequestComment);
            }
        }
        if (transitioned) {
            if (!comment.toString().isEmpty()) {
                comment.append("\n");
            }
            comment.append(BugAuditConfig.reopeningNotificationComment);
        }
        if (!comment.toString().isEmpty()) {
            issue.addComment(new BugAuditContent(comment.toString()));
            return true;
        }
        return transitioned;
    }

    private boolean transitionIssue(List<String> transitions, BatIssue issue) {
        try {
            if (transitions.size() > 1) {
                StringBuilder consoleLog = new StringBuilder();
                consoleLog.append("Transitioning the issue ")
                        .append(issue.getKey()).append(": ").append(transitions.get(0));
                for (int i = 1; i < transitions.size(); i++) {
                    consoleLog.append(" -> ").append(transitions.get(i));
                    BatIssueFactory moveStatus = new BatIssueFactory();
                    moveStatus.setStatus(transitions.get(i));
                    tracker.updateIssue(issue, moveStatus);
                }
                System.out.print(consoleLog.toString());
                return true;
            }
        } catch (Exception e) {
            exceptions.add(e);
            e.printStackTrace();
        }
        return false;
    }

    private void processBug(Bug bug, BugAuditScanResult result) throws BugAuditException {
        BatSearchQuery searchQuery = new BatSearchQuery(BatSearchQuery.Condition.type, BatSearchQuery.Operator.equals, config.getIssueType());
        Set<String> searchLabels = new HashSet<>(result.getKeys());
        searchLabels.addAll(bug.getKeys());
        searchLabels.add(result.getTool());
        searchLabels.add(result.getBugAuditLabel());
        searchLabels.add(result.getLang().toString());
        searchLabels.add(result.getRepo().toString());
        searchQuery.add(BatSearchQuery.Condition.label, BatSearchQuery.Operator.equals, new ArrayList<>(searchLabels));
        List<BatIssue> batIssues = tracker.searchBatIssues(config.getProject(), searchQuery, BugAuditConfig.maxSearchResult);
        if (batIssues.size() == 0) {
            if (readOnly) {
                System.out.println("Read-Only Mode: Skipped creating issue: " + bug.getTitle());
            } else {
                createBatIssueForBug(bug);
            }
        } else if (batIssues.size() == 1) {
            if (readOnly) {
                System.out.println("Read-Only Mode: Skipped updating issue: " +
                        batIssues.get(0).getKey() + ": " + batIssues.get(0).getTitle());
            } else {
                updateBatIssueForBug(batIssues.get(0), bug);
            }
        } else {
            throw new BugAuditException("More than one issue listed:\n"
                    + "Labels: " + Arrays.toString(bug.getKeys().toArray()) + "\n"
                    + "Issues: " + Arrays.toString(batIssues.toArray()));
        }
    }

    private void printChangelog() {
        StringBuilder changelog = new StringBuilder();
        changelog.append("\n[BUILD CHANGELOG] ");
        Set<String> totalUpdates = new HashSet<>(tracker.getUpdatedIssues());
        totalUpdates.addAll(tracker.getCommentedIssues());
        int created = tracker.getCreatedIssues().size();
        int updated = totalUpdates.size();
        if (created == 0 && updated == 0) {
            changelog.append("No change(s)");
        } else {
            if (created > 0) {
                changelog.append("Created ").append(created).append(" issue");
                if (created > 1) {
                    changelog.append("s");
                }
                if (updated > 0) {
                    changelog.append(" and ");
                }
            }
            if (updated > 0) {
                changelog.append("Updated ").append(updated).append(" issue");
                if (updated > 1) {
                    changelog.append("s");
                }
            }
        }
        System.out.println(changelog);
    }

    void processResult() {
        System.out.println("Vulnerabilities found: " + scanResult.getBugs().size());
        for (Bug bug : scanResult.getBugs()) {
            try {
                processBug(bug, scanResult);
            } catch (BugAuditException e) {
                e.printStackTrace();
                exceptions.add(e);
            }
        }
        if (config.isClosingAllowed()) {
            BatSearchQuery searchQuery = new BatSearchQuery(BatSearchQuery.Condition.type, BatSearchQuery.Operator.equals, config.getIssueType());
            List<String> tags = new ArrayList<>(scanResult.getKeys());
            tags.add(scanResult.getTool());
            tags.add(scanResult.getBugAuditLabel());
            tags.add(scanResult.getLang().toString());
            tags.add(scanResult.getRepo().toString());
            searchQuery.add(BatSearchQuery.Condition.label, BatSearchQuery.Operator.equals, tags);
            searchQuery.add(BatSearchQuery.Condition.status, BatSearchQuery.Operator.not, config.getClosedStatuses());
            List<BatIssue> batIssues = tracker.searchBatIssues(config.getProject(), searchQuery, BugAuditConfig.maxSearchResult);
            for (BatIssue batIssue : batIssues) {
                try {
                    if (!isVulnerabilityExists(batIssue, scanResult.getBugs())) {
                        if (readOnly) {
                            System.out.println("Read-Only Mode: Skipped closing issue: " +
                                    batIssue.getKey() + ": " + batIssue.getTitle());
                        } else {
                            if (closeIssue(batIssue)) {
                            } else {
                                System.out.println(batIssue.getKey() + ": No action taken now.");
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    exceptions.add(e);
                }
            }
        }
        printChangelog();
    }

    List<Exception> getExceptions() {
        return exceptions;
    }
}
