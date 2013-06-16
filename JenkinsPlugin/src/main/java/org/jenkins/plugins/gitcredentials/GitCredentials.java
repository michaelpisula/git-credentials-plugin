package org.jenkins.plugins.gitcredentials;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Describable;
import hudson.model.EnvironmentList;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Cause.UserIdCause;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import jenkins.model.Jenkins;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.Stapler;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUser;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.google.common.collect.Lists;

public class GitCredentials extends BuildWrapper {

	public final User user;
	public final System system;

	@DataBoundConstructor
	public GitCredentials(User user, System system) {
		this.user = user;
		this.system = system;
	}

	public User getUser() {
		return user;
	}

	public System getSystem() {
		return system;
	}

	@Override
	public Environment setUp(AbstractBuild build, Launcher launcher,
			BuildListener listener) throws IOException, InterruptedException {
		GitCredentialLogger logger = new GitCredentialLogger(listener);
		List<SSHUserPrivateKey> keys = Lists.newLinkedList();
		if (user != null) {

			UserIdCause cause = (UserIdCause) build.getCause(UserIdCause.class);
			if (cause == null) {
				logger.error("Job must be started by a user for user credentials, will try system credentials");
			} else {
				hudson.model.User currentUser = hudson.model.User.get(cause
						.getUserId());
				for (SSHUserPrivateKey u : CredentialsProvider
						.lookupCredentials(SSHUserPrivateKey.class,
								(Item) null, currentUser.impersonate())) {
					keys.add(u);
				}
				if (user.userFail && keys.isEmpty()) {
					logger.error("No credentials found for user "
							+ currentUser.getDisplayName());
					build.setResult(Result.FAILURE);
				}
			}
		} else {
			logger.info("No user credential lookup configured");
		}
		if (system != null) {
			for (SSHUserPrivateKey key : CredentialsProvider.lookupCredentials(
					SSHUserPrivateKey.class, ACL.SYSTEM)) {
				if (key.getId().equals(system.systemUser)) {
					keys.add(key);
				}
			}
		} else {
			logger.info("No system credential lookup configured");
		}

		if (keys.isEmpty()) {
			logger.error("No usable credentials were found");
			return null;
		}
		SSHUserPrivateKey key = keys.get(0);
		if (keys.size() > 1) {
			logger.info("Found more than one usable credential, using credentials for username "
					+ key.getUsername());
		}

		return createEnvironment(key, logger, build);
	}

	/**
	 * Helper method that returns a safe description of a {@link SSHUser}.
	 * 
	 * @param sshUser
	 *            the credentials.
	 * @return the description.
	 */
	private static String description(SSHUser sshUser) {
		return StringUtils.isEmpty(sshUser.getDescription()) ? sshUser
				.getUsername() : sshUser.getDescription();
	}

	public GitCredentialEnvironment createEnvironment(SSHUserPrivateKey key,
			GitCredentialLogger logger, AbstractBuild build) {

		GitCredentialEnvironment result = new GitCredentialEnvironment();

		FilePath userContent = Jenkins.getInstance().getRootPath()
				.child("userContent");
		result.tempFile = createAndWriteToTempFile(logger, userContent,
				key.getPrivateKey(), "key");
		result.gitSshFile = createAndWriteToTempFile(logger, userContent,
				"#!/bin/bash\nssh -i " + result.tempFile.getRemote()
						+ " \"$@\"", "gitSSH");

		EnvironmentList environments = build.getEnvironments();
		hudson.model.Environment environment = environments.get(0);
		HashMap<String, String> env = new HashMap<String, String>();
		env.put("GIT_SSH", result.gitSshFile.getRemote());
		environment.buildEnvVars(env);

		return result;
	}

	private FilePath createAndWriteToTempFile(GitCredentialLogger logger,
			FilePath userContent, String content, String name) {
		FilePath file;
		try {

			file = userContent.createTempFile(name, "sh");
			file.write(content, "UTF-8");
		} catch (Exception e1) {
			logger.error("Could not create temp file " + name);
			return null;
		}
		return file;
	}

	public class GitCredentialEnvironment extends Environment {

		private FilePath tempFile;
		private FilePath gitSshFile;

		@Override
		public boolean tearDown(AbstractBuild build, BuildListener listener)
				throws IOException, InterruptedException {
			tempFile.delete();
			gitSshFile.delete();
			return true;
		}

	}

	/**
	 * Our descriptor.
	 */
	@Extension
	public static class GitCredentialsDescriptorImpl extends
			BuildWrapperDescriptor {

		/**
		 * {@inheritDoc}
		 */
		@Override
		public boolean isApplicable(AbstractProject<?, ?> item) {
			return true;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public String getDisplayName() {
			return "Git Credentials";
		}

		/**
		 * Populate the list of credentials available to the job.
		 * 
		 * @return the list box model.
		 */
		public ListBoxModel doFillSystemUserItems() {
			ListBoxModel m = new ListBoxModel();

			Item item = Stapler.getCurrentRequest().findAncestorObject(
					Item.class);
			// we only want the users with private keys as they are the only
			// ones valid for an agent
			for (SSHUser u : CredentialsProvider.lookupCredentials(
					SSHUserPrivateKey.class, item, ACL.SYSTEM)) {
				m.add(description(u), u.getId());
			}

			return m;
		}

	}

	public static class User implements Describable<User> {
		public final boolean userFail;

		@DataBoundConstructor
		public User(boolean userFail) {
			this.userFail = userFail;
		}

		public boolean isUserFail() {
			return userFail;
		}

		public Descriptor<User> getDescriptor() {
			return new Descriptor<GitCredentials.User>() {

				@Override
				public String getDisplayName() {
					return "User";
				}
			};
		}

	}

	public static class System implements Describable<System> {
		public String systemUser;

		@DataBoundConstructor
		public System(String systemUser) {
			this.systemUser = systemUser;
		}

		public String getSystemUser() {
			return systemUser;
		}

		public Descriptor<System> getDescriptor() {
			return Jenkins.getInstance().getDescriptor(getClass());
		}

		@Extension
		public static class SystemDecscriptorImpl extends Descriptor<System> {

			@Override
			public String getDisplayName() {
				return "System";
			}

		}
	}

}
