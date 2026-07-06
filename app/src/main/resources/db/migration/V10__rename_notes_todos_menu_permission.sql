-- 统一权限码命名风格：将 notes/todos 菜单权限码修正为单数形式，与 file:menu 保持一致
UPDATE t_permission
SET code = 'note:menu'
WHERE code = 'notes:menu'
  AND delete_at = 0
  AND NOT EXISTS (SELECT 1 FROM t_permission WHERE code = 'note:menu' AND delete_at = 0);

UPDATE t_permission
SET code = 'todo:menu'
WHERE code = 'todos:menu'
  AND delete_at = 0
  AND NOT EXISTS (SELECT 1 FROM t_permission WHERE code = 'todo:menu' AND delete_at = 0);
