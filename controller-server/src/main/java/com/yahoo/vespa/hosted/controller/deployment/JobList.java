// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.deployment;

import com.yahoo.collections.AbstractFilteringList;
import com.yahoo.component.Version;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ApplicationVersion;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A list of deployment jobs that can be filtered in various ways.
 *
 * @author jonmv
 */
public class JobList extends AbstractFilteringList<JobStatus, JobList> {

    private JobList(Collection<? extends JobStatus> jobs, boolean negate) {
        super(jobs, negate, JobList::new);
    }

    // ----------------------------------- Factories

    public static JobList from(Collection<? extends JobStatus> jobs) {
        return new JobList(jobs, false);
    }

    // ----------------------------------- Basic filters

    /** Returns the subset of jobs which are currently upgrading */
    public JobList upgrading() {
        return matching(job ->    job.isRunning()
                               && job.lastSuccess().isPresent()
                               && job.lastSuccess().get().versions().targetPlatform().isBefore(job.lastTriggered().get().versions().targetPlatform()));
    }

    /** Returns the subset of jobs which are currently failing */
    public JobList failing() {
        return matching(job -> ! job.isSuccess());
    }

    /** Returns the subset of jobs which must be failing due to an application change */
    public JobList failingApplicationChange() {
        return matching(JobList::failingApplicationChange);
    }

    /** Returns the subset of jobs which are failing with the given run status */
    public JobList withStatus(RunStatus status) {
        return matching(job -> job.lastStatus().map(status::equals).orElse(false));
    }

    /** Returns the subset of jobs of the given type -- most useful when negated */
    public JobList type(JobType... types) {
        return matching(job -> List.of(types).contains(job.id().type()));
    }

    /** Returns the subset of jobs of which are production jobs */
    public JobList production() {
        return matching(job -> job.id().type().isProduction());
    }

    // ----------------------------------- JobRun filtering

    /** Returns the list in a state where the next filter is for the lastTriggered run type */
    public RunFilter lastTriggered() {
        return new RunFilter(JobStatus::lastTriggered);
    }

    /** Returns the list in a state where the next filter is for the lastCompleted run type */
    public RunFilter lastCompleted() {
        return new RunFilter(JobStatus::lastCompleted);
    }

    /** Returns the list in a state where the next filter is for the lastSuccess run type */
    public RunFilter lastSuccess() {
        return new RunFilter(JobStatus::lastSuccess);
    }

    /** Returns the list in a state where the next filter is for the firstFailing run type */
    public RunFilter firstFailing() {
        return new RunFilter(JobStatus::firstFailing);
    }


    /** Allows sub-filters for runs of the given kind */
    public class RunFilter {

        private final Function<JobStatus, Optional<Run>> which;

        private RunFilter(Function<JobStatus, Optional<Run>> which) {
            this.which = which;
        }

        /** Returns the subset of jobs where the run of the given type exists */
        public JobList present() {
            return matching(run -> true);
        }

        /** Returns the subset of jobs where the run of the given type occurred before the given instant */
        public JobList startedBefore(Instant threshold) {
            return matching(run -> run.start().isBefore(threshold));
        }

        /** Returns the subset of jobs where the run of the given type occurred after the given instant */
        public JobList startedAfter(Instant threshold) {
            return matching(run -> run.start().isAfter(threshold));
        }

        /** Returns the subset of jobs where the run of the given type was on the given version */
        public JobList on(ApplicationVersion version) {
            return matching(run -> run.versions().targetApplication().equals(version));
        }

        /** Returns the subset of jobs where the run of the given type was on the given version */
        public JobList on(Version version) {
            return matching(run -> run.versions().targetPlatform().equals(version));
        }

        /** Transforms the JobRun condition to a JobStatus condition, by considering only the JobRun mapped by which, and executes */
        private JobList matching(Predicate<Run> condition) {
            return JobList.this.matching(job -> which.apply(job).filter(condition).isPresent());
        }

    }

    // ----------------------------------- Internal helpers

    private static boolean failingApplicationChange(JobStatus job) {
        if (job.isSuccess()) return false;
        if (job.lastSuccess().isEmpty()) return true; // An application which never succeeded is surely bad.
        if ( ! job.firstFailing().get().versions().targetPlatform().equals(job.lastSuccess().get().versions().targetPlatform())) return false; // Version change may be to blame.
        return ! job.firstFailing().get().versions().targetApplication().equals(job.lastSuccess().get().versions().targetApplication()); // Return whether there is an application change.
    }

}
