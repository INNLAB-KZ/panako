package be.panako.strategy.panako.storage;

/**
 * Extended metadata for PANAKO tracks stored via the HTTP API.
 *
 * <p>Wraps the upstream {@link PanakoResourceMetadata} POJO and adds two
 * optional descriptive fields used by deployments that index tracks without
 * an ISRC identifier:</p>
 * <ul>
 *   <li>{@link #title} — combined "artist:title" string supplied by the client</li>
 *   <li>{@link #audioUrl} — origin URL of the audio referenced by the track</li>
 * </ul>
 *
 * <p>Both fields are nullable. Tracks stored through the legacy ISRC code
 * paths leave them {@code null}.</p>
 */
public class PanakoResourceMetadataExt {

	public final PanakoResourceMetadata base;
	public final String title;
	public final String audioUrl;

	public PanakoResourceMetadataExt(PanakoResourceMetadata base, String title, String audioUrl) {
		this.base = base;
		this.title = title;
		this.audioUrl = audioUrl;
	}
}
