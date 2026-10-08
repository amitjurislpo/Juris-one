-- Reverses V1__baseline.sql: the four original Prisma migrations, undone newest first.
-- Flyway Community has no undo: run with psql, then delete the version 1 row from flyway_schema_history.
-- THIS DROPS EVERY TABLE AND ALL DATA. Take a backup first.

-- ===== 20261006193828_tax_parents_senior =====
-- Reverse of tax_parents_senior
ALTER TABLE "tax_profiles" DROP COLUMN "parents_senior";

-- ===== 20261006143825_attendance_engine =====
-- Reverse of attendance_engine
DROP TABLE "job_runs";
ALTER TABLE "attendance_days" DROP COLUMN "label", DROP COLUMN "leave_days", DROP COLUMN "lop_days", DROP COLUMN "missed_checkout";
-- Postgres can't drop an enum value: rebuild the type without NOT_TRACKED.
DELETE FROM "attendance_days" WHERE "status" = 'NOT_TRACKED';
ALTER TYPE "DayStatus" RENAME TO "DayStatus_old";
CREATE TYPE "DayStatus" AS ENUM ('PRESENT', 'HALF_DAY', 'ABSENT', 'LEAVE', 'HOLIDAY', 'WEEKLY_OFF', 'BEFORE_JOINING', 'AFTER_EXIT', 'PENDING');
ALTER TABLE "attendance_days" ALTER COLUMN "status" TYPE "DayStatus" USING ("status"::text::"DayStatus");
DROP TYPE "DayStatus_old";

-- ===== 20261006134356_department_archive =====
-- Reverse of 20261006134356_department_archive
ALTER TABLE "departments" DROP COLUMN "archived_at";

-- ===== 20261005220955_init =====
-- DropForeignKey
ALTER TABLE "public"."departments" DROP CONSTRAINT "departments_head_employee_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."departments" DROP CONSTRAINT "departments_created_by_fkey";

-- DropForeignKey
ALTER TABLE "public"."role_permissions" DROP CONSTRAINT "role_permissions_role_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."role_assignable_roles" DROP CONSTRAINT "role_assignable_roles_role_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."role_assignable_roles" DROP CONSTRAINT "role_assignable_roles_assignable_role_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."employees" DROP CONSTRAINT "employees_department_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."employees" DROP CONSTRAINT "employees_reporting_manager_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."employees" DROP CONSTRAINT "employees_role_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."employees" DROP CONSTRAINT "employees_shift_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."employees" DROP CONSTRAINT "employees_created_by_fkey";

-- DropForeignKey
ALTER TABLE "public"."users" DROP CONSTRAINT "users_employee_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."sessions" DROP CONSTRAINT "sessions_employee_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."login_history" DROP CONSTRAINT "login_history_employee_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."auth_tokens" DROP CONSTRAINT "auth_tokens_employee_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."punches" DROP CONSTRAINT "punches_employee_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."attendance_days" DROP CONSTRAINT "attendance_days_employee_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."regularization_requests" DROP CONSTRAINT "regularization_requests_employee_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."regularization_requests" DROP CONSTRAINT "regularization_requests_decided_by_fkey";

-- DropForeignKey
ALTER TABLE "public"."holidays" DROP CONSTRAINT "holidays_created_by_fkey";

-- DropForeignKey
ALTER TABLE "public"."leave_ledger" DROP CONSTRAINT "leave_ledger_employee_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."leave_ledger" DROP CONSTRAINT "leave_ledger_leave_type_fkey";

-- DropForeignKey
ALTER TABLE "public"."leave_requests" DROP CONSTRAINT "leave_requests_employee_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."leave_requests" DROP CONSTRAINT "leave_requests_decided_by_fkey";

-- DropForeignKey
ALTER TABLE "public"."leave_requests" DROP CONSTRAINT "leave_requests_leave_type_fkey";

-- DropForeignKey
ALTER TABLE "public"."salary_structures" DROP CONSTRAINT "salary_structures_employee_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."salary_structures" DROP CONSTRAINT "salary_structures_created_by_fkey";

-- DropForeignKey
ALTER TABLE "public"."payroll_runs" DROP CONSTRAINT "payroll_runs_approved_by_fkey";

-- DropForeignKey
ALTER TABLE "public"."payroll_runs" DROP CONSTRAINT "payroll_runs_closed_by_fkey";

-- DropForeignKey
ALTER TABLE "public"."payroll_adjustments" DROP CONSTRAINT "payroll_adjustments_run_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."payroll_adjustments" DROP CONSTRAINT "payroll_adjustments_employee_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."payroll_adjustments" DROP CONSTRAINT "payroll_adjustments_updated_by_fkey";

-- DropForeignKey
ALTER TABLE "public"."payslips" DROP CONSTRAINT "payslips_run_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."payslips" DROP CONSTRAINT "payslips_employee_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."tax_profiles" DROP CONSTRAINT "tax_profiles_employee_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."declaration_items" DROP CONSTRAINT "declaration_items_employee_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."declaration_items" DROP CONSTRAINT "declaration_items_decided_by_fkey";

-- DropForeignKey
ALTER TABLE "public"."notifications" DROP CONSTRAINT "notifications_employee_id_fkey";

-- DropForeignKey
ALTER TABLE "public"."audit_log" DROP CONSTRAINT "audit_log_actor_id_fkey";

-- DropTable
DROP TABLE "public"."departments";

-- DropTable
DROP TABLE "public"."roles";

-- DropTable
DROP TABLE "public"."role_permissions";

-- DropTable
DROP TABLE "public"."role_assignable_roles";

-- DropTable
DROP TABLE "public"."shifts";

-- DropTable
DROP TABLE "public"."employees";

-- DropTable
DROP TABLE "public"."users";

-- DropTable
DROP TABLE "public"."sessions";

-- DropTable
DROP TABLE "public"."login_history";

-- DropTable
DROP TABLE "public"."auth_tokens";

-- DropTable
DROP TABLE "public"."punches";

-- DropTable
DROP TABLE "public"."attendance_days";

-- DropTable
DROP TABLE "public"."regularization_requests";

-- DropTable
DROP TABLE "public"."holidays";

-- DropTable
DROP TABLE "public"."leave_types";

-- DropTable
DROP TABLE "public"."leave_ledger";

-- DropTable
DROP TABLE "public"."leave_requests";

-- DropTable
DROP TABLE "public"."salary_structures";

-- DropTable
DROP TABLE "public"."payroll_runs";

-- DropTable
DROP TABLE "public"."payroll_adjustments";

-- DropTable
DROP TABLE "public"."payslips";

-- DropTable
DROP TABLE "public"."tax_years";

-- DropTable
DROP TABLE "public"."tax_sections";

-- DropTable
DROP TABLE "public"."tax_profiles";

-- DropTable
DROP TABLE "public"."declaration_items";

-- DropTable
DROP TABLE "public"."notifications";

-- DropTable
DROP TABLE "public"."audit_log";

-- DropTable
DROP TABLE "public"."settings";

-- DropTable
DROP TABLE "public"."email_outbox";

-- DropEnum
DROP TYPE "public"."EmployeeStatus";

-- DropEnum
DROP TYPE "public"."Region";

-- DropEnum
DROP TYPE "public"."HolidayRegion";

-- DropEnum
DROP TYPE "public"."PunchKind";

-- DropEnum
DROP TYPE "public"."PunchSource";

-- DropEnum
DROP TYPE "public"."DayStatus";

-- DropEnum
DROP TYPE "public"."RegularizationKind";

-- DropEnum
DROP TYPE "public"."RequestStatus";

-- DropEnum
DROP TYPE "public"."LeavePart";

-- DropEnum
DROP TYPE "public"."LedgerReason";

-- DropEnum
DROP TYPE "public"."PayrollStatus";

-- DropEnum
DROP TYPE "public"."PayrollStage";

-- DropEnum
DROP TYPE "public"."StructureReason";

-- DropEnum
DROP TYPE "public"."TaxRegime";

-- DropEnum
DROP TYPE "public"."DeclarationStatus";

-- DropEnum
DROP TYPE "public"."AuthTokenPurpose";

-- DropEnum
DROP TYPE "public"."EmailStatus";

