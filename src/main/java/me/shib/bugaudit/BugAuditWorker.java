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
        for (String key : scanResult.getKeys()) {
            query.add(BatSearchQuery.Condition.label, BatSearchQuery.Operator.matching, key);
        }
        query.add(BatSearchQuery.Condition.label, BatSearchQuery.Operator.matching, scanResult.getLang().toString());
        query.add(BatSearchQuery.Condition.label, BatSearchQuery.Operator.matching, scanResult.getTool());
        query.add(BatSearchQuery.Condition.label, BatSearchQuery.Operator.matching, scanResult.getRepo().toString());
        return BugAuditTracker.getTracker(config.getPriorityMap(), query, projects);
    }

    private void createBatIssueForBug(Bug bug) throws BugAuditException {
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
    }

    private boolean isLabelExsitingInSet(Set<String> fromIssue, String labelForAvailabilityCheck) {
        for (String label : fromIssue) {
            if (label.equalsIgnoreCase(labelForAvailabilityCheck)) {
                return true;
            }
        }
        return false;
    }

    private void updateBatIssueForBug(BatIssue batIssue, Bug bug) throws BugAuditException {
        if (config.isIssueIgnorable(batIssue)) {
            System.out.println("Ignoring the issue: " + batIssue.getKey());
        }
        boolean issueUpdated = false;
        BatIssueFactory batIssueFactory = new BatIssueFactory();
        batIssueFactory.setProject(config.getProject());
        if (batIssue.getAssignee() == null && config.getUsers().getAssignee() != null) {
            batIssueFactory.setAssignee(config.getUsers().getAssignee());
            issueUpdated = true;
        }
        if (config.isSummaryUpdateAllowed() && !batIssue.getTitle().contentEquals(bug.getTitle())) {
            batIssueFactory.setTitle(bug.getTitle());
            issueUpdated = true;
        }
        if (config.isDescriptionUpdateAllowed() &&
                !tracker.areContentsMatching(bug.getDescription(), batIssue.getDescription())) {
            batIssueFactory.setDescription(bug.getDescription());
            issueUpdated = true;
        }
        if (config.isLabelUpdateAllowed()) {
            Set<String> updateSet = new HashSet<>(batIssue.getLabels());
            for (String labelFromBug : bug.getTags()) {
                if (!isLabelExsitingInSet(updateSet, labelFromBug)) {
                    updateSet.add(labelFromBug);
                }
            }
            if (updateSet.size() != batIssue.getLabels().size()) {
                batIssueFactory.setLabels(new ArrayList<>(updateSet));
                issueUpdated = true;
            }
        }
        StringBuilder comment = new StringBuilder();
        if (((batIssue.getPriority().getValue() < bug.getPriority()) && (config.isReprioritizeAllowed()))
                || ((batIssue.getPriority().getValue() > bug.getPriority()) && (config.isDeprioritizeAllowed()))) {
            batIssueFactory.setPriority(bug.getPriority());
            System.out.println("Prioritizing " + batIssue.getKey() + " to " + tracker.getPriorityName(bug.getPriority()) + " based on actual priority.");
            comment.append("Prioritizing to **").append(tracker.getPriorityName(bug.getPriority())).append("** based on actual priority.");
            issueUpdated = true;
        } else if ((batIssue.getPriority().getValue() > bug.getPriority()) && (config.isDeprioritizeAllowed())) {
            batIssueFactory.setPriority(bug.getPriority());
            System.out.println("Reducing priority " + batIssue.getKey() + " to " + tracker.getPriorityName(bug.getPriority()) + " based on actual priority.");
            comment.append("Reducing priority to **").append(tracker.getPriorityName(bug.getPriority())).append("** based on actual priority.");
            issueUpdated = true;
        }
        if (issueUpdated) {
            batIssue = tracker.updateIssue(batIssue, batIssueFactory);
            if (!comment.toString().isEmpty()) {
                batIssue.addComment(new BugAuditContent(comment.toString()));
            }
        }
        if (config.isOpeningAllowedForStatus(batIssue.getStatus())) {
            reopenIssue(batIssue);
        } else if (issueUpdated) {
            System.out.println("Updated the issue: " + batIssue.getKey() + " - "
                    + batIssue.getTitle());
        } else {
            System.out.println("Issue up-to date: " + batIssue.getKey() + " - "
                    + batIssue.getTitle());
        }
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

    private boolean closeIssue(BatIssue issue) throws BugAuditException {
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

    private void reopenIssue(BatIssue issue) throws BugAuditException {
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
        }
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
                System.out.println(consoleLog.toString());
                return true;
            }
        } catch (Exception e) {
            exceptions.add(e);
            e.printStackTrace();
        }
        return false;
    }

    private void processBug(Bug bug, BugAuditScanResult result) throws BugAuditException {
        BatSearchQuery searchQuery = new BatSearchQuery(BatSearchQuery.Condition.type, BatSearchQuery.Operator.matching, config.getIssueType());
        searchQuery.add(BatSearchQuery.Condition.label, BatSearchQuery.Operator.matching, result.getRepo().toString());
        searchQuery.add(BatSearchQuery.Condition.label, BatSearchQuery.Operator.matching, result.getLang().toString());
        searchQuery.add(BatSearchQuery.Condition.label, BatSearchQuery.Operator.matching, result.getBugAuditLabel());
        searchQuery.add(BatSearchQuery.Condition.label, BatSearchQuery.Operator.matching, result.getTool());
        for (String key : bug.getKeys()) {
            searchQuery.add(BatSearchQuery.Condition.label, BatSearchQuery.Operator.matching, key);
        }
        List<BatIssue> batIssues = tracker.searchBatIssues(config.getProject(), searchQuery);
        if (batIssues.size() == 0) {
            createBatIssueForBug(bug);
        } else if (batIssues.size() == 1) {
            updateBatIssueForBug(batIssues.get(0), bug);
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

    void processResult() throws BugAuditException {
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
            BatSearchQuery searchQuery = new BatSearchQuery(BatSearchQuery.Condition.type, BatSearchQuery.Operator.matching, config.getIssueType());
            searchQuery.add(BatSearchQuery.Condition.label, BatSearchQuery.Operator.matching, scanResult.getRepo().toString());
            searchQuery.add(BatSearchQuery.Condition.label, BatSearchQuery.Operator.matching, scanResult.getTool());
            searchQuery.add(BatSearchQuery.Condition.label, BatSearchQuery.Operator.matching, scanResult.getBugAuditLabel());
            searchQuery.add(BatSearchQuery.Condition.label, BatSearchQuery.Operator.matching, scanResult.getLang().toString());
            searchQuery.add(BatSearchQuery.Condition.status, BatSearchQuery.Operator.not_matching, config.getClosedStatuses());
            List<BatIssue> batIssues = tracker.searchBatIssues(config.getProject(), searchQuery);
            for (BatIssue batIssue : batIssues) {
                try {
                    if (!isVulnerabilityExists(batIssue, scanResult.getBugs())) {
                        if (!closeIssue(batIssue)) {
                            System.out.println(batIssue.getKey() + ": No action taken now.");
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
