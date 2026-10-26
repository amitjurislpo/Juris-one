-- A punch recorded from a Juris Track time event (start / end of the workday) is told apart from a web punch,
-- so My attendance and the register can show where it came from.
ALTER TYPE "PunchSource" ADD VALUE IF NOT EXISTS 'JURISTRACK';
