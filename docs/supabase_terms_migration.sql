-- Vigília — Terms acceptance + profile auto-provisioning migration
--
-- Run this SQL in BOTH the prod and staging Supabase SQL Editor.
--
-- What this does:
--   1. Adds tos_version / tos_accepted_at / privacy_version / privacy_accepted_at
--      to public.profiles.
--   2. Creates handle_new_user() trigger on auth.users that auto-creates a
--      corresponding profile row using metadata passed by the client during
--      signUpWith(Email). Idempotent via ON CONFLICT DO NOTHING.
--   3. Backfills profile rows for any auth.users that are missing one (the
--      original orphan bug). Aceites ficam null, forçando a tela de termos
--      no próximo login.
--
-- Safe to re-run: uses IF NOT EXISTS / CREATE OR REPLACE / DROP TRIGGER IF EXISTS.

-- ---------------------------------------------------------------------------
-- 1. Colunas novas em profiles
-- ---------------------------------------------------------------------------
ALTER TABLE public.profiles
  ADD COLUMN IF NOT EXISTS tos_version         TEXT,
  ADD COLUMN IF NOT EXISTS tos_accepted_at     TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS privacy_version     TEXT,
  ADD COLUMN IF NOT EXISTS privacy_accepted_at TIMESTAMPTZ;

-- ---------------------------------------------------------------------------
-- 2. Trigger que cria profile ao inserir em auth.users
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  INSERT INTO public.profiles (
    id,
    full_name,
    tos_version,
    tos_accepted_at,
    privacy_version,
    privacy_accepted_at
  ) VALUES (
    NEW.id,
    COALESCE(NEW.raw_user_meta_data->>'full_name', split_part(NEW.email, '@', 1)),
    NEW.raw_user_meta_data->>'tos_version',
    NULLIF(NEW.raw_user_meta_data->>'tos_accepted_at', '')::TIMESTAMPTZ,
    NEW.raw_user_meta_data->>'privacy_version',
    NULLIF(NEW.raw_user_meta_data->>'privacy_accepted_at', '')::TIMESTAMPTZ
  )
  ON CONFLICT (id) DO NOTHING;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
  AFTER INSERT ON auth.users
  FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();

-- ---------------------------------------------------------------------------
-- 3. Safety net: cria profile para todo auth.users sem par em profiles.
--    Aceites ficam null → primeiro login desse usuário força a tela de termos.
-- ---------------------------------------------------------------------------
INSERT INTO public.profiles (id, full_name)
SELECT u.id, split_part(u.email, '@', 1)
FROM auth.users u
LEFT JOIN public.profiles p ON p.id = u.id
WHERE p.id IS NULL;

-- ---------------------------------------------------------------------------
-- 4. Verificação (deve retornar 0 rows depois do INSERT acima)
-- ---------------------------------------------------------------------------
-- SELECT u.id, u.email
-- FROM auth.users u
-- LEFT JOIN public.profiles p ON p.id = u.id
-- WHERE p.id IS NULL;

-- ---------------------------------------------------------------------------
-- 5. RLS check — confirme manualmente no dashboard que profiles permite
--    UPDATE onde auth.uid() = id. O auto-heal do client depende disso
--    para atualizar aceites quando bumparmos a versão dos termos.
-- ---------------------------------------------------------------------------
