using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using Windows.Storage;

namespace NemoclawChat_Windows.Services;

public sealed record VoicePreferences(
    string Voice = "if_sara",
    double Speed = 1.08,
    bool WakeWord = false,
    bool PushToTalk = false,
    bool ShowTranscript = false,
    string ParticleShape = VoicePreferencesStore.SphereShape,
    string WakePhrase = VoicePreferencesStore.DefaultWakePhrase);

public static class VoicePreferencesStore
{
    public const string SphereShape = "sphere";
    public const string NeuralCoreShape = "neural-core";
    public const string HermesHeadShape = "hermes-head";
    public const string DefaultWakePhrase = "Hermes";
    public static readonly string[] SupportedVoices = ["if_sara", "im_nicola"];
    public static readonly string[] SupportedParticleShapes = [SphereShape, NeuralCoreShape, HermesHeadShape];
    public static readonly string[] SupportedWakePhrases = [DefaultWakePhrase, "Ehi Hermes", "Ok Hermes"];
    private static readonly Regex WakeTokenRegex = new(@"[\p{L}\p{N}]+", RegexOptions.Compiled);
    public static event Action? Changed;

    public static VoicePreferences Load(string? projectId)
    {
        var values = ApplicationData.Current.LocalSettings.Values;
        var key = Key(projectId);
        var raw = values[key] as string;
        if (string.IsNullOrWhiteSpace(raw) && !string.IsNullOrWhiteSpace(projectId))
        {
            raw = values[Key(string.Empty)] as string;
        }

        var parts = (raw ?? "if_sara|1.08|False|False|False").Split('|');
        var requestedVoice = parts.ElementAtOrDefault(0) ?? "if_sara";
        var voice = SupportedVoices.Contains(requestedVoice, StringComparer.OrdinalIgnoreCase)
            ? SupportedVoices.First(item => item.Equals(requestedVoice, StringComparison.OrdinalIgnoreCase))
            : "if_sara";
        var speed = double.TryParse(parts.ElementAtOrDefault(1), NumberStyles.Float, CultureInfo.InvariantCulture, out var parsedSpeed)
            ? Math.Clamp(parsedSpeed, 0.75, 1.35)
            : 1.08;
        var requestedParticleShape = parts.ElementAtOrDefault(5) ?? SphereShape;
        var particleShape = NormalizeParticleShape(requestedParticleShape);
        var requestedWakePhrase = parts.ElementAtOrDefault(6) ?? DefaultWakePhrase;
        var wakePhrase = NormalizeWakePhrase(requestedWakePhrase);
        var preferences = new VoicePreferences(
            voice,
            speed,
            bool.TryParse(parts.ElementAtOrDefault(2), out var wake) && wake,
            bool.TryParse(parts.ElementAtOrDefault(3), out var pushToTalk) && pushToTalk,
            bool.TryParse(parts.ElementAtOrDefault(4), out var transcript) && transcript,
            particleShape,
            wakePhrase);

        if (!string.Equals(requestedVoice, voice, StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(requestedParticleShape, particleShape, StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(requestedWakePhrase, wakePhrase, StringComparison.Ordinal))
        {
            Save(projectId, preferences);
        }
        return preferences;
    }

    public static void Save(string? projectId, VoicePreferences preferences)
    {
        var voice = SupportedVoices.Contains(preferences.Voice, StringComparer.OrdinalIgnoreCase)
            ? preferences.Voice
            : "if_sara";
        var speed = Math.Clamp(preferences.Speed, 0.75, 1.35);
        var particleShape = NormalizeParticleShape(preferences.ParticleShape);
        var wakePhrase = NormalizeWakePhrase(preferences.WakePhrase);
        ApplicationData.Current.LocalSettings.Values[Key(projectId)] = string.Join('|',
            voice,
            speed.ToString(CultureInfo.InvariantCulture),
            preferences.WakeWord,
            preferences.PushToTalk,
            preferences.ShowTranscript,
            particleShape,
            wakePhrase);
        Changed?.Invoke();
    }

    public static string NormalizeParticleShape(string? value) =>
        SupportedParticleShapes.FirstOrDefault(item => item.Equals(value, StringComparison.OrdinalIgnoreCase)) ?? SphereShape;

    public static string NormalizeWakePhrase(string? value)
    {
        var normalized = Regex.Replace((value ?? string.Empty).Replace('|', ' ').Trim(), @"\s+", " ");
        if (normalized.Length > 48)
        {
            normalized = normalized[..48].TrimEnd();
        }
        return WakeTokenRegex.IsMatch(normalized) ? normalized : DefaultWakePhrase;
    }

    public static bool TryStripWakePhrase(string transcript, string? wakePhrase, out string command)
    {
        command = transcript.Trim();
        var phraseTokens = WakeTokenRegex.Matches(NormalizeWakePhrase(wakePhrase));
        var transcriptTokens = WakeTokenRegex.Matches(transcript);
        if (phraseTokens.Count == 0 || transcriptTokens.Count < phraseTokens.Count)
        {
            return false;
        }

        var matchStart = -1;
        for (var transcriptIndex = 0; transcriptIndex <= transcriptTokens.Count - phraseTokens.Count; transcriptIndex++)
        {
            var matches = true;
            for (var phraseIndex = 0; phraseIndex < phraseTokens.Count; phraseIndex++)
            {
                if (!WakeTokensEqual(transcriptTokens[transcriptIndex + phraseIndex].Value, phraseTokens[phraseIndex].Value))
                {
                    matches = false;
                    break;
                }
            }
            if (matches)
            {
                matchStart = transcriptIndex;
                break;
            }
        }
        if (matchStart < 0)
        {
            return false;
        }

        var finalToken = transcriptTokens[matchStart + phraseTokens.Count - 1];
        var end = finalToken.Index + finalToken.Length;
        command = transcript[end..].TrimStart(' ', ',', ':', ';', '.', '-', '!', '?');
        return true;
    }

    private static bool WakeTokensEqual(string left, string right)
    {
        var normalizedLeft = NormalizeToken(left);
        var normalizedRight = NormalizeToken(right);
        return string.Equals(normalizedLeft, normalizedRight, StringComparison.Ordinal) ||
               (normalizedLeft is "hermes" or "ermes") && (normalizedRight is "hermes" or "ermes");
    }

    private static string NormalizeToken(string value)
    {
        var builder = new StringBuilder();
        foreach (var character in value.Normalize(NormalizationForm.FormD))
        {
            if (CharUnicodeInfo.GetUnicodeCategory(character) != UnicodeCategory.NonSpacingMark)
            {
                builder.Append(char.ToLowerInvariant(character));
            }
        }
        return builder.ToString();
    }

    private static string Key(string? projectId) => $"VoiceProfile:{projectId?.Trim() ?? string.Empty}";
}
