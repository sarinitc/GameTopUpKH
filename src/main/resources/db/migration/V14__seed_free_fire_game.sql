-- Ensure the canonical Free Fire catalog row exists.
--
-- Player verification resolves the game by slug before calling the provider,
-- so a missing row returns GAME_NOT_FOUND even when Volsever is configured
-- correctly. This migration is idempotent: databases that already contain the
-- row are left unchanged. Free Fire is keyed by player ID alone (no zone).
INSERT INTO games (name, slug, player_id_label, requires_zone_id, is_active)
SELECT 'Free Fire', 'free-fire', 'Player ID', FALSE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM games WHERE slug = 'free-fire');
