namespace NemoclawChat_Windows.Services;

public sealed class AppSettings
{
    public string GatewayUrl { get; set; } = "http://hermes:8642/v1";
    public string GatewayWsUrl { get; set; } = string.Empty;
    public string AdminBridgeUrl { get; set; } = "http://hermes:8642";
    public string Provider { get; set; } = "hermes-agent";
    public string InferenceEndpoint { get; set; } = "http://hermes:8642/v1";
    public string PreferredApi { get; set; } = "hermes-native";
    public string Model { get; set; } = "hermes-agent";
    public string AccessMode { get; set; } = "Tailscale/LAN";
    public string VisualBlocksMode { get; set; } = "auto";
    public string VideoLibraryPath { get; set; } = string.Empty;
    public string ActiveProjectId { get; set; } = string.Empty;
    public string ActiveProjectName { get; set; } = string.Empty;
    public bool StrictNativeMode { get; set; } = false;
    public bool DemoMode { get; set; } = false;
    public bool AdvancedChatDetails { get; set; } = false;
    public bool ShowToolCalls { get; set; } = true;
    public bool ShowMessageMetrics { get; set; } = false;
    public bool MetricTtft { get; set; } = true;
    public bool MetricTokensPerSecond { get; set; } = true;
    public bool MetricOutputTokens { get; set; } = true;
    public bool MetricPromptTokens { get; set; } = true;
    public bool MetricContextTokens { get; set; } = true;
    public bool MetricDuration { get; set; } = true;
    public int MaxAttachmentMb { get; set; } = 6;
}
