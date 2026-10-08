# iOS → Android parity port

Questo progetto contiene la conversione Android/Jetpack Compose dell'intero layer di viste del progetto iOS originale fornito dall'utente.

La corrispondenza è vista-per-vista e componente-per-componente: struttura delle schermate, gerarchia dei pannelli, dialoghi/sheet, menu, stati vuoti, gruppi playlist, source cards, impostazioni, dettagli media, player, EPG, ricerca, login/splash, VPN, parental lock, Trakt e componenti Liquid Glass condivisi.

## Mappa principale

- ContentView → `ui/ios/ContentView.kt` + `IosParityShell.kt`
- HomeView → `HomeView.kt`
- ChannelGridView → `ChannelGridView.kt`
- ChannelsView → `ChannelsView.kt`
- M3UChannelsView → `M3UChannelsView.kt`
- EPGGridView → `EPGGridView.kt`
- EPGManageView → `EPGManageView.kt`
- SourcesView / AddPlaylistView / ImportSourcesSheet / MergePlaylistView → `SourcesView.kt`
- SourceManageView / EditSourceDetailsView / ManageSourceContentView → `SourceManageView.kt`
- SourceManagerView → `SourceManagerView.kt`
- SettingsView → `SettingsView.kt`
- GlobalSearchView → `GlobalSearchView.kt`
- MovieDetailView → `MovieDetailView.kt`
- SeriesEpisodesView → `SeriesEpisodesView.kt`
- AlternateSourcesView → `AlternateSourcesView.kt`
- PlayerView → `PlayerView.kt` + esistente `IosPlayerScreen.kt`
- AdaptivePlayerView → `AdaptivePlayerView.kt`
- PlayerInfoOverlay → `PlayerInfoOverlay.kt`
- ContinueWatchingSection → `ContinueWatchingSection.kt`
- HomeCustomization → `HomeCustomization.kt`
- GlobalToolbarButtons → `GlobalToolbarButtons.kt`
- MediaDetailComponents → `MediaDetailComponents.kt`
- MediaDetailScrollTopBar → `MediaDetailScrollTopBar.kt`
- TMDBEnrichedPoster → `TMDBEnrichedPoster.kt`
- MetadataSettingsView → `MetadataSettingsView.kt`
- PersonalVPNView → `PersonalVPNView.kt`
- ParentalLockView → `ParentalLockView.kt`
- TraktConnectView → `TraktConnectView.kt`
- DebugConsoleView → `DebugConsoleView.kt`
- ATSDiagnosticView → `ATSDiagnosticView.kt`
- AllSourcesLiveView → `AllSourcesLiveView.kt`
- BufferSettingsView → `BufferSettingsView.kt`
- LoginView → `LoginView.kt`
- SplashScreenView → `SplashScreenView.kt`

## Liquid Glass

I componenti SwiftUI sotto `Views/LiquidGlass/` sono stati consolidati nel port Android `LiquidGlassPort.kt` sopra `PlayerGlassButton`, `IosGlassCard` e i relativi helper:

- GlassButton / GlassIconGlyph
- GlassCard / GlassCardBackground
- GlassListStyle / GlassListRow / GlassSectionHeader / GlassSourceIcon / GlassSourceRowLabel / GlassMenuPillLabel
- GlassSettingsRow / GlassSettingsToggleRow / GlassRowDivider
- GlassTabBar / screen background helpers
- ResumeConfirmationOverlay

Tutte le azioni principali usano la stessa superficie `PlayerGlassButton`; i compatibility shims nella UI Android precedente instradano inoltre i pulsanti Material classici verso la stessa implementazione glass.

## Tema

`GassPlayerTheme` ora usa palette iOS-like coerenti e rispetta `light`, `dark` e `system`. Le nuove superfici glass derivano da `MaterialTheme.colorScheme`, quindi seguono automaticamente il tema scelto.

## Regression checks

- nessuna assegnazione `seekParameters =` nel `PlaybackController`; viene usato `setSeekParameters(...)`;
- nessun `BasicAlertDialog` residuo;
- la chiamata `PlayerGlassButton` con trailing lambda nel player è stata resa non ambigua;
- i sorgenti delle nuove viste hanno delimitatori bilanciati e nessun placeholder `TODO/FIXME` introdotto.

## Build environment

La compilazione Gradle non è stata completata nell'ambiente di lavoro perché il wrapper richiede Gradle 9.8.0 e l'ambiente non riesce a risolvere `services.gradle.org`. Il progetto è quindi consegnato con controlli statici e regression checks completati.
