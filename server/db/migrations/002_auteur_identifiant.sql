-- Identifiant d'auteur immuable sur les contributions éditables (audit § F13).
--
-- Jusqu'ici, « être l'auteur » se prouvait en comparant un prénom. Un compte
-- supprimé libère son prénom ; le prochain inscrit à le reprendre héritait donc
-- du droit de modifier et d'effacer les commentaires et les photos de son
-- prédécesseur. Le prénom reste affiché — c'est lui que les collègues lisent —
-- mais il ne décide plus de rien.
--
-- Réexécutable : IF NOT EXISTS sur ADD COLUMN, et les UPDATE ne touchent que
-- les lignes encore sans identifiant.
--
-- Pas de clé étrangère vers `users`, volontairement : ON DELETE CASCADE
-- effacerait les contributions d'un compte supprimé (des relevés de terrain
-- que l'équipe utilise), et SET NULL effacerait la provenance. La suppression
-- d'un compte est traitée par l'API, qui détache ses contributions sans les
-- perdre.
-- ---------------------------------------------------------------------------
ALTER TABLE pm_comments ADD COLUMN IF NOT EXISTS author_user_id INT NULL AFTER author;
ALTER TABLE pm_photos   ADD COLUMN IF NOT EXISTS author_user_id INT NULL AFTER author;

-- Rattachement des contributions existantes à leur auteur, par le prénom —
-- le seul lien disponible. Si un prénom avait déjà été repris avant cette
-- migration, le rattachement fige l'erreur ; ne rien rattacher la figerait
-- tout autant, en laissant la comparaison de prénoms faire foi pour toujours.
-- Aucune donnée de production n'est dans ce cas (deux comptes, jamais
-- supprimés, plus le compte d'essai).
UPDATE pm_comments c JOIN users u ON LOWER(u.username) = LOWER(c.author)
   SET c.author_user_id = u.id
 WHERE c.author_user_id IS NULL;

UPDATE pm_photos p JOIN users u ON LOWER(u.username) = LOWER(p.author)
   SET p.author_user_id = u.id
 WHERE p.author_user_id IS NULL;
