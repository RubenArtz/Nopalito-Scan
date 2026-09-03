<div align="center">
  <img src="https://nopalitoscan.org/icon/logo.png" alt="Nopalito Scan logo" width="180" />

  <h1>Nopalito Scan</h1>

  <p>An Android document-scanning app built on privacy, simplicity, and open source.</p>
</div>

---

Nopalito Scan is an Android document-scanning app and a fork
of [FairScan](https://github.com/pynicolas/FairScan).

It builds on FairScan's simple, privacy-respecting scanning experience while providing a separate
project identity and a foundation for continued development.

> **Upstream project:** [pynicolas/FairScan](https://github.com/pynicolas/FairScan)

## About

Nopalito Scan helps users scan paper documents quickly from an Android device and produce clean,
shareable PDF files.

The app focuses on an uncomplicated workflow:

1. Scan one or more pages.
2. Review the result when needed.
3. Save or share the generated PDF.

## Features

- Automatic document detection
- Automatic perspective correction
- Automatic image enhancement
- Fast multi-page PDF generation
- Clean, distraction-free Android interface
- On-device document processing
- Minimal permissions
- No ads and no tracking
- Open-source software licensed under GPLv3

## Privacy

Nopalito Scan is designed around privacy. Document detection, image processing, and PDF creation run
locally on the Android device. The app does not require cloud processing for its core scanning
workflow.

## Optional Cloud Backend

For an enhanced experience, Nopalito Scan offers an optional cloud backend that provides:

- **50 MB free storage** for documents and conversions
- Cloud-based document processing and conversions (optional features)
- Cross-device sync and backup when signed in
- QR code generation with public, immutable URLs

All cloud features are opt-in. Local scanning works fully without an account or internet connection.
When cloud features are used, files are transmitted via HTTPS/TLS and temporary processing files are
automatically deleted after the operation completes.

## Architecture & Security Model

Nopalito Scan uses a **hybrid architecture**:

| Component     | License     | Visibility                                    |
|---------------|-------------|-----------------------------------------------|
| Android App   | GPLv3+      | **Open source** (this repository)             |
| Cloud Backend | Proprietary | **Closed source** (operated by Nopalito Scan) |

### How file processing works

**Local-first (default, no account needed):**

- Document detection, perspective correction, image enhancement, PDF generation, OCR — all run *
  *entirely on your device**
- Zero data leaves your phone unless you explicitly use a cloud feature

**Cloud features (opt-in, requires account):**

1. You select a file and choose a cloud action (upload, convert, generate QR, etc.)
2. File is sent via **HTTPS/TLS** (certificate validation enforced, cleartext blocked) to the
   Nopalito Scan backend
3. Backend processes the request (conversion, OCR, compression, etc.)
4. Result is returned to the app / stored in your cloud storage
5. **Temporary processing files are deleted automatically**: upload staging ≤1 hour, conversion
   working dirs ≤24 hours
6. Stored files remain until you delete them (then moved to trash for 30 days)

## Based on FairScan

This project is a fork of [FairScan](https://github.com/pynicolas/FairScan), an Android document
scanner created to be simple and respectful.

FairScan provides the upstream foundation for the scanning workflow and document-processing stack,
including automatic document detection, perspective correction, image enhancement, and PDF
generation. Please see the upstream repository for its history, contributors, documentation, and
original work.

## Technical Stack

Nopalito Scan inherits and builds on an Android stack that includes:

| Component                  | Purpose                                         |
|----------------------------|-------------------------------------------------|
| Kotlin and Jetpack Compose | Android application and user interface          |
| CameraX                    | Camera capture                                  |
| LiteRT                     | On-device document segmentation model inference |
| OpenCV                     | Perspective correction and image enhancement    |
| Tesseract                  | Optical character recognition (OCR)             |
| PDFBox-Android             | PDF generation                                  |

## Requirements

- Android 8.0 (API level 26) or later
- A device with a camera
- Android Studio and a compatible Android SDK for development

## Build

Clone this repository and build it using Gradle:

```bash
./gradlew clean check assembleRelease
```

To build an Android App Bundle:

```bash
./gradlew clean check :app:bundleRelease
```

The generated artifacts will be available in the corresponding Gradle build output directories.

## Development

1. Clone this repository.
2. Open the project in Android Studio.
3. Allow Gradle synchronization to finish.
4. Select an emulator or a physical Android device running Android 8.0 or later.
5. Run the `app` configuration.

## Contributing

Contributions are welcome. For substantial changes, please open an issue first to discuss the
proposed work.

- Keep changes focused and clearly described.
- Follow the existing Kotlin and Android project conventions.
- Add or update tests when behavior changes.
- Respect the GPLv3 license and preserve applicable copyright and license notices.

## Acknowledgments

Nopalito Scan is based on [FairScan](https://github.com/pynicolas/FairScan) by its authors and
contributors. Thank you to the FairScan community for the open-source foundation that makes this
fork possible.

## License

Nopalito Scan is distributed under the GNU General Public License v3.0 or later (GPLv3+).
See [LICENSE](LICENSE) for the complete license text.

This fork retains the licensing obligations and relevant notices from its upstream project.
