-- Only loaded by the loopback-only local Compose stack; production provisions its own account.
CREATE USER IF NOT EXISTS 'gym_dev'@'%' IDENTIFIED BY 'local-gym-dev-only';
GRANT 'gym_app' TO 'gym_dev'@'%';
SET DEFAULT ROLE 'gym_app' TO 'gym_dev'@'%';
