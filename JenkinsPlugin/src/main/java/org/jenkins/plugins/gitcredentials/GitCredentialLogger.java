package org.jenkins.plugins.gitcredentials;

import hudson.model.TaskListener;

public class GitCredentialLogger {

	private TaskListener listener;

	public GitCredentialLogger(TaskListener listener) {
		this.listener = listener;
	}

	public TaskListener getListener() {
		return listener;
	}

	public void info(String message) {
		listener.getLogger().println("[GitCredentials] - " + message);
	}

	public void error(String message) {
		listener.getLogger().println("[GitCredentials] - [ERROR] - " + message);
	}
}
