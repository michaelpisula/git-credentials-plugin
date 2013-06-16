package org.jenkins.plugins.gitcredentials;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Describable;
import hudson.model.EnvironmentList;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

	private User user;
	private System system;

	@DataBoundConstructor
	public GitCredentials(User user, System system) {
		this.user = user;
		this.system = system;
	}

	public void setUser(User user) {
		this.user = user;
	}

	public void setSystem(System system) {
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
			for (SSHUserPrivateKey u : CredentialsProvider.lookupCredentials(
					SSHUserPrivateKey.class, (Item) null, hudson.model.User
							.current().impersonate())) {
				keys.add(u);
			}
			if (user.userFail && keys.isEmpty()) {
				logger.error("No credentials found for user "
						+ hudson.model.User.current().getDisplayName());
				build.setResult(Result.FAILURE);
			}
		}
		if (system != null) {
			for (SSHUserPrivateKey key : CredentialsProvider.lookupCredentials(
					SSHUserPrivateKey.class, ACL.SYSTEM)) {
				if (key.getId().equals(system.systemUser)) {
					keys.add(key);
				}
			}
		}

		if (keys.isEmpty()) {
			logger.error("No usable credentials were found");
			return super.setUp(build, launcher, listener);
		}

		SSHUserPrivateKey key = keys.get(0);

		if (keys.size() > 1) {
			logger.info("Found more than one usable credential, using credentials for username "
					+ key.getUsername());
		}

		Path tempfile = Files.createTempFile("gitssh", "tmp");
		Files.write(tempfile, key.getPrivateKey().getBytes());

		Path gitSshFile = Paths.get(Jenkins.getInstance().getRootDir()
				.getPath(), "userContent", "gitSSH.sh");
		if (!Files.isExecutable(gitSshFile)) {
			Files.deleteIfExists(gitSshFile);
			OutputStream outputStream = null;
			try {
				outputStream = Files.newOutputStream(gitSshFile);
			} finally {
				if (outputStream != null)
					outputStream.close();
			}

		}

		EnvironmentList environments = build.getEnvironments();
		hudson.model.Environment environment = environments.get(0);
		HashMap<String, String> env = new HashMap<String, String>();
		env.put("GIT_SSH", gitSshFile.toString());
		environment.buildEnvVars(env);
		return super.setUp(build, launcher, listener);
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
		private boolean userFail;

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
		private String systemUser;

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

	}

	@Extension
	public static class SystemDecscriptorImpl extends Descriptor<System> {

		@Override
		public String getDisplayName() {
			return "System";
		}

	}
}
