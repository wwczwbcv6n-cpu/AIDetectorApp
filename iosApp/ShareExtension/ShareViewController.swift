import UIKit
import Social
import shared // Import the shared KMM module

class ShareViewController: UIViewController {

    // The KMM constructor used to default to a hardcoded LAN IP. It now
    // requires a baseUrl. We read both base URL and API key from the
    // shared App Group UserDefaults that the main app writes to under
    // the same suite. If the user hasn't configured the server yet we
    // surface a clear error instead of firing a request to nowhere.
    //
    // App Group used by both the main app and this extension:
    //   group.com.myapplication.shared
    private static let appGroup = "group.com.myapplication.shared"
    private static let urlKey = "apiBaseUrl"
    private static let keyKey = "apiKey"

    private lazy var api: AIDetectorApi? = {
        let defaults = UserDefaults(suiteName: Self.appGroup)
        guard let url = defaults?.string(forKey: Self.urlKey),
              !url.isEmpty else { return nil }
        let key = defaults?.string(forKey: Self.keyKey)
        return AIDetectorApi(baseUrl: url, apiKey: key)
    }()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .white

        // Setup UI
        let titleLabel = UILabel()
        titleLabel.text = "AI Detector"
        titleLabel.font = .boldSystemFont(ofSize: 20)
        titleLabel.textAlignment = .center
        
        let statusLabel = UILabel()
        statusLabel.text = "Analyzing..."
        statusLabel.font = .systemFont(ofSize: 16)
        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 0
        
        let doneButton = UIButton(type: .system)
        doneButton.setTitle("Done", for: .normal)
        doneButton.addTarget(self, action: #selector(doneTapped), for: .touchUpInside)

        let stackView = UIStackView(arrangedSubviews: [titleLabel, statusLabel, doneButton])
        stackView.axis = .vertical
        stackView.spacing = 20
        stackView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stackView)
        
        NSLayoutConstraint.activate([
            stackView.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            stackView.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            stackView.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            stackView.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20)
        ])

        // Handle the shared image
        handleSharedImage(statusLabel: statusLabel)
    }

    private func handleSharedImage(statusLabel: UILabel) {
        guard let item = extensionContext?.inputItems.first as? NSExtensionItem,
              let attachment = item.attachments?.first else {
            self.showError(message: "No image found.", statusLabel: statusLabel)
            return
        }

        let imageType = "public.image"
        if attachment.hasItemConformingToTypeIdentifier(imageType) {
            attachment.loadItem(forTypeIdentifier: imageType, options: nil) { [weak self] (imageData, error) in
                DispatchQueue.main.async {
                    guard let self = self, error == nil else {
                        self?.showError(message: "Failed to load image.", statusLabel: statusLabel)
                        return
                    }

                    var image: UIImage? = nil
                    if let url = imageData as? URL, let data = try? Data(contentsOf: url) {
                        image = UIImage(data: data)
                    } else if let img = imageData as? UIImage {
                        image = img
                    }

                    guard let finalImage = image, let jpegData = finalImage.jpegData(compressionQuality: 0.8) else {
                        self.showError(message: "Could not process image.", statusLabel: statusLabel)
                        return
                    }
                    
                    let kotlinByteArray = self.toKotlinByteArray(data: jpegData)

                    // Refuse to fire if the user hasn't configured the
                    // server in the main app yet — better than silently
                    // hitting a hardcoded IP that won't reach them.
                    guard let api = self.api else {
                        self.showError(
                            message: "Open the AI Detector app and set the API URL before sharing.",
                            statusLabel: statusLabel)
                        return
                    }

                    // KMP suspend functions are exposed to Swift with completion handlers
                    api.analyzeImage(imageData: kotlinByteArray) { result, error in
                        DispatchQueue.main.async {
                            if let result = result {
                                self.showResult(result, statusLabel: statusLabel)
                            } else {
                                self.showError(message: error?.localizedDescription ?? "Unknown error", statusLabel: statusLabel)
                            }
                        }
                    }
                }
            }
        } else {
            self.showError(message: "Shared item is not an image.", statusLabel: statusLabel)
        }
    }
    
    private func toKotlinByteArray(data: Data) -> shared.KotlinByteArray {
        let swiftByteArray = [UInt8](data)
        let int8Array = swiftByteArray.map { Int8(bitPattern: $0) }
        let kotlinByteArray = shared.KotlinByteArray(size: Int32(int8Array.count))
        for (index, element) in int8Array.enumerated() {
            kotlinByteArray.set(index: Int32(index), value: element)
        }
        return kotlinByteArray
    }

    private func showResult(_ result: AnalysisResult, statusLabel: UILabel) {
        let conclusion = result.conclusion
        let probability = result.aiProbability
        
        statusLabel.text = """
        Conclusion: \(conclusion)
        AI Probability: \(String(format: "%.2f%%", probability * 100))
        """
        
        if conclusion.lowercased() == "ai-generated" {
            statusLabel.textColor = .systemRed
        } else {
            statusLabel.textColor = .systemGreen
        }
    }

    private func showError(message: String, statusLabel: UILabel) {
        statusLabel.text = "Error: \(message)"
        statusLabel.textColor = .systemRed
    }

    @objc private func doneTapped() {
        // Complete the request and dismiss the extension
        self.extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
    }
}
