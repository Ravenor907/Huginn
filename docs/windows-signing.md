# Signing Huginn.exe for Windows

Windows Defender trusts a program much more when it is signed. SignPath Foundation signs open
source projects for free. CI is already set up for it: once the two secrets below exist, every
build of `main` sends Huginn.exe to SignPath, waits for it to come back signed, and zips the signed
copy. Without the secrets, that step is skipped and nothing else changes.

Do one step at a time. Each step is one thing to do.

## Part 1: get ready (about 10 minutes)

1. Open https://github.com/settings/security in your browser.
2. Turn on two-factor authentication if it is not on already. SignPath requires it.
3. Open https://github.com/Ravenor907/Huginn-Muninn/releases and check that the newest release
   has Huginn-Windows.zip on it. SignPath wants to see a project that ships releases.

## Part 2: apply (about 10 minutes, then wait)

1. Open https://signpath.org/apply in your browser.
2. Fill in the form with these answers:
   - Project name: `Huginn and Muninn`
   - Repository: `https://github.com/Ravenor907/Huginn-Muninn`
   - License: `GPL-3.0`
   - What gets signed: `Huginn.exe in Huginn-Windows.zip, built by GitHub Actions`
   - Your name or handle: `Ravenor907`
   - Email: the address you use for GitHub
3. Send the form.
4. Wait for their email. It can take a few days to a few weeks.
5. If the email asks for a code signing policy on the project page, reply to the person handling
   your application and ask to have the text at the end of this file added to the README.

## Part 3: set up the project at SignPath (after the approval email)

1. Sign in at https://app.signpath.io with the account from the email.
2. Write down your Organization ID. It is shown on the start page (a long code with dashes).
3. Open Projects and add a project with the slug `Huginn-Muninn`.
4. In that project, open Artifact Configurations and add one with the slug `huginn-windows`.
5. Paste this into it and save:

   ```xml
   <artifact-configuration xmlns="http://signpath.io/artifact-configuration/v1">
     <zip-file>
       <pe-file path="Huginn.exe" product-name="Huginn">
         <authenticode-sign/>
       </pe-file>
     </zip-file>
   </artifact-configuration>
   ```

6. In the same project, open Signing Policies and check that one has the slug `release-signing`.
   (SignPath usually makes it. If it has another name, see "Different names" below.)
7. Open Trusted Build Systems and link `GitHub.com` to the project, as their page explains.
8. Open Users, add a CI user, and create an API token for it. Copy the token.

## Part 4: give CI the two secrets (about 5 minutes)

1. Open https://github.com/Ravenor907/Huginn-Muninn/settings/secrets/actions
2. Click "New repository secret".
3. Name: `SIGNPATH_API_TOKEN`. Value: the token from Part 3, step 8. Click "Add secret".
4. Click "New repository secret" again.
5. Name: `SIGNPATH_ORGANIZATION_ID`. Value: the Organization ID from Part 3, step 2. Click "Add secret".

## Part 5: the first signed build

1. The next build of `main` sends Huginn.exe to SignPath.
2. SignPath emails you to approve it. Click Approve.
3. The build finishes and the job notes say "Signed".

That is all. From then on, approve each release in the SignPath email.

### Different names

If SignPath gave the project, policy or artifact configuration other names, set them as repository
variables (not secrets) at https://github.com/Ravenor907/Huginn-Muninn/settings/variables/actions:
`SIGNPATH_PROJECT_SLUG`, `SIGNPATH_POLICY_SLUG`, `SIGNPATH_ARTIFACT_SLUG`.

## Until it is signed: tell Microsoft it is a false alarm

This helps right away, signed or not. Do it once for each new release that gets flagged.

1. Open https://www.microsoft.com/en-us/wdsi/filesubmission
2. Choose "Home customer" or "Software developer" (developer lets you say it is your own program).
3. Sign in with a Microsoft account.
4. Choose "Microsoft Defender Antivirus".
5. Upload Huginn-Windows.zip from the release.
6. Detection name: the one Defender showed (for example `Trojan:Win32/Bearfoos.A!ml`).
7. Choose "Incorrectly detected as malware".
8. In the notes, write: `Open source chat app, source at https://github.com/Ravenor907/Huginn-Muninn`.
9. Send it. Microsoft usually answers within a few days.

## Text for the README (only once SignPath has approved the project)

```markdown
## Code signing policy

Free code signing provided by [SignPath.io](https://about.signpath.io), certificate by
[SignPath Foundation](https://signpath.org).

- Committers and approvers: [Ravenor907](https://github.com/Ravenor907)
- Every signed Huginn.exe is built from this repository by GitHub Actions.

Privacy: Huginn and Muninn ask GitHub for updates and send your messages and calls to the people
you write to. They send nothing else to anyone.
```
