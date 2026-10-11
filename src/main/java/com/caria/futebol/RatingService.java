package com.caria.futebol;

import java.util.concurrent.*;

/** Compatibility adapter for older Google rating polls. New deployments use NativeVoting. */
final class RatingService implements AutoCloseable {
    private final FootballStore store;
    private final String guild;
    private final FormsGateway gateway;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r,"google-rating-sync"); thread.setDaemon(true); return thread;
    });
    private volatile boolean syncFailed;
    RatingService(FootballStore store, String guild, FormsGateway gateway) { this.store=store; this.guild=guild; this.gateway=gateway; }
    boolean configured() { return gateway != null; }
    boolean syncFailed() { return syncFailed; }
    void start() { if (configured()) worker.scheduleWithFixedDelay(this::sync,1,60,TimeUnit.SECONDS); }
    private void requireConfigured() {
        if (!configured()) throw new FootballException("Legacy Google Forms integration is not configured. Use built-in website voting.");
    }
    synchronized RatingPoll add(String name) {
        requireConfigured();
        RatingPoll poll = store.addPlayerForVoting(guild,name);
        try { return create(poll.id()); }
        catch (FootballException error) { syncFailed = true; return store.ratingPoll(guild,poll.id()); }
    }
    synchronized RatingPoll create(String id) {
        requireConfigured(); RatingPoll poll=store.ratingPoll(guild,id);
        if (!poll.status().equals("CREATING")) return poll;
        String url = gateway.create(poll.id(),poll.playerName());
        store.attachRatingForm(guild,id,url); return store.ratingPoll(guild,id);
    }
    synchronized RatingPoll update(String id, boolean close) {
        requireConfigured(); RatingPoll poll=store.ratingPoll(guild,id);
        if (poll.status().equals("CLOSED")) {
            cleanupSafely(id); return store.ratingPoll(guild,id);
        }
        if (poll.status().equals("CREATING")) poll=create(id);
        VoteSnapshot votes=gateway.read(poll.id(),close);
        if (close && !votes.closed()) throw new FootballException("Google did not close voting. Retry shortly.");
        store.updateRatingVotes(guild,id,votes.scores(),votes.closed());
        if (votes.closed()) cleanupSafely(id);
        return store.ratingPoll(guild,id);
    }
    synchronized void cleanup(String id) {
        requireConfigured();
        if (!store.ratingCleanupPending(guild,id)) return;
        gateway.delete(id);
        store.finishRatingCleanup(guild,id);
    }
    private void cleanupSafely(String id) {
        try { cleanup(id); }
        catch (RuntimeException error) { syncFailed=true; }
    }
    void sync() {
        boolean failed=false;
        try {
            for (RatingPoll poll : store.ratingPolls(guild)) {
                if (poll.status().equals("CLOSED") || store.nativePoll(poll.id())) continue;
                try { update(poll.id(),false); }
                catch (RuntimeException e) { failed=true; }
            }
            for (String id : store.pendingRatingCleanup(guild)) {
                try { cleanup(id); }
                catch (RuntimeException e) { failed=true; }
            }
        } catch (RuntimeException e) { failed=true; }
        syncFailed=failed;
    }
    public void close() { worker.shutdownNow(); }
}
