# Contributing to AndyClaw

Welcome! We're thrilled you're interested in contributing to AndyClaw, the open-source AI assistant for Android, built for the dGEN1 and ethOS. This guide outlines how you can help us build a robust and useful AI assistant for the decentralized future.

## Our Vision

AndyClaw aims to bring the power of advanced AI and decentralized technologies to mobile devices, inspired by the capabilities of OpenClaw. We focus on integrating crypto-native features, device control, a thriving skills ecosystem, and user privacy.

## Code of Conduct

Please read and adhere to our [Code of Conduct](CODE_OF_CONDUCT.md). (Note: If a Code of Conduct file doesn't exist yet, it would be a good idea to create one.)

## How to Contribute

### 1. Reporting Bugs

Found a bug? We appreciate you reporting it! Please check if your bug has already been reported. If not, please open a new issue with the following details:

*   **Device Model & OS Version:** (e.g., dGEN1, Android 13)
*   **AndyClaw Version:** (e.g., v18, or from Play Store/GitHub build)
*   **Steps to Reproduce:** Clear, concise steps to trigger the bug.
*   **Expected Behavior:** What should have happened?
*   **Actual Behavior:** What actually happened?
*   **Screenshots/Videos:** If applicable, to illustrate the issue.
*   **Logcat Output:** Relevant logs from Logcat can be extremely helpful.

### 2. Suggesting Features or Improvements

Have an idea for a new feature or a way to improve AndyClaw?

*   **Check Existing Issues:** See if your idea has already been suggested.
*   **Open an Issue:** If not, please open a new issue to discuss your idea. This allows the community to provide feedback before you start coding.

### 3. Submitting a Pull Request (PR)

We welcome your code contributions!

1.  **Fork the Repository:** Create your own fork of the `EthereumPhone/AndyClaw` repository.
2.  **Create a New Branch:** Make your changes on a new branch (e.g., `fix/apk-parse-error` or `feature/add-prayer-skill`).
3.  **Make Your Changes:** Write clean, well-commented code. Follow Android and Kotlin best practices.
4.  **Add Tests:** If you're adding a new feature or fixing a bug that can be tested, please include unit or integration tests.
5.  **Lint and Format:** Ensure your code adheres to standard Android linting and Kotlin formatting.
6.  **Write a Clear Commit Message:** Describe what your changes do and why.
7.  **Submit a Pull Request:** Open a PR against the `main` branch of the `EthereumPhone/AndyClaw` repository.
    *   Clearly describe your changes in the PR description.
    *   Link to any relevant issue (e.g., `Fixes #5`).

### 4. Development Setup

Here's a quick guide to get you started with building AndyClaw:

*   **Prerequisites:**
    *   Android Studio
    *   Git
    *   Kotlin
*   **Clone the Repository:**
    ```bash
    git clone https://github.com/EthereumPhone/AndyClaw.git
    cd AndyClaw
    ```
*   **API Keys & Configuration:**
    *   For ethOS devices, most features work without API keys as they leverage system services.
    *   For specific LLM providers (OpenRouter, Tinfoil) or custom gateways (`PREMIUM_LLM_URL`), you might need to create a `local.properties` file in the project root with your keys. Example:
      ```properties
      # Optional — only needed for specific LLM providers or custom gateways
      ALCHEMY_API=your_alchemy_api_key
      BUNDLER_API=your_pimlico_bundler_key
      PREMIUM_LLM_URL=https://your-custom-llm-gateway.com/api/llm
      ```
*   **Build the APK:**
    ```bash
    ./gradlew assembleRelease
    ```
    The generated APK will be in `app/build/outputs/apk/release/`.
*   **Dependencies:** The project uses [JitPack](https://jitpack.io) for its SDKs. You might need to add the JitPack repository to your `settings.gradle.kts` if you're building a separate project that uses the SDKs.

### **Project Philosophy**

AndyClaw is inspired by OpenClaw, aiming to bring powerful AI and decentralized features to mobile. We value:

*   **Open Source:** Everything is public and auditable.
*   **User Sovereignty:** Putting the user in control of their data and AI.
*   **On-Chain Integration:** Leveraging crypto-native capabilities for mobile.
*   **Extensibility:** A robust skills system for community contributions.

### **Communication**

*   **For discussions, help, and collaboration:** Join the Freedom Factory Discord server: [https://discord.com/invite/2WHw6UBmYn](https://discord.com/invite/2WHw6UBmYn)
*   **For specific technical issues and PRs:** Use GitHub Issues and Pull Requests.

---

Thanks for being a part of the Freedom Factory and dGEN1 community! Your contributions help make AndyClaw better for everyone.
