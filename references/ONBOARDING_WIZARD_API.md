# Employee onboarding wizard: API contract (v1, shared by backend and frontend)

Source of truth for fields, options and rules: `Juris_One_Onboarding_Spec.pdf` (owner has it) and the prompt in `Claude_Code_Prompt_Juris_One_Onboarding.md`.
All JSON field names are camelCase, dates are ISO `YYYY-MM-DD`, money is a number in rupees (the form field "Last Drawn Salary" is typed in rupees; store as integer paise),
errors are RFC 7807 problem details; field errors come as `errors: { "<jsonPath>": "message" }` (e.g. `"education[1].institute"`).
Every endpoint is for the signed-in employee's OWN onboarding only (`/mine`). Not signed in = 401. Onboarding not required for this employee = 404.
After SUBMITTED every write answers 409 ("Onboarding is already submitted.").

## Status
`NOT_REQUIRED` (old employees; wizard never shown) | `NOT_STARTED` | `IN_PROGRESS` | `SUBMITTED` | `REOPENED` (HR reopened; wizard editable again).
Steps are numbered 1..6: 1 personal, 2 employment, 3 statutory, 4 family, 5 nominations, 6 attachments.
`stepStatus`: `{ "1": "COMPLETE"|"INCOMPLETE"|"UNVISITED", ... }`.

## Endpoints
| Method + path | Purpose |
|---|---|
| `GET /api/onboarding/mine` | everything: header, status, currentStep, stepStatus, all step data, attachment slots with files |
| `PUT /api/onboarding/mine/steps/{step}` | save one step (1..6 except 6 which has no body, see below). Body = that step's object. Server validates; saves even when incomplete? NO: invalid = 422 with `errors`, nothing saved. Success returns the full `GET` payload with `currentStep` = next step. |
| `PUT /api/onboarding/mine/current-step` | `{ "step": n }` remember where the employee is (stepper click) without saving data |
| `POST /api/onboarding/mine/attachments` | multipart: `slotKey`, `file`; 201 with the attachment; pdf/doc/docx/jpg/png, max 10 MB, else 400/413 |
| `DELETE /api/onboarding/mine/attachments/{id}` | remove a file |
| `GET /api/onboarding/mine/attachments/{id}/file?download=true|false` | stream a file (own only) |
| `POST /api/onboarding/mine/submission` | validates ALL steps + mandatory attachment slots; 422 with `errors` if anything is missing; success sets SUBMITTED + submittedAt |
| `PUT /api/people/{id}/onboarding/status` | HR (`people.add` or the permission used for employees): `{ "status": "REOPENED" }` |
| `GET /api/people/{id}/onboarding` | HR read-only view: same payload as `/mine` (numbers masked, `?reveal` NOT supported) plus attachments |
| `GET /api/people/{id}/onboarding/attachments/{fileId}/file?download=true|false` | HR streams/downloads one of the employee's onboarding files (same permission as `GET /api/people/{id}/onboarding`; 404 if the file is not that employee's) |
| `GET /api/people/{id}/documents` (+ `/{docId}/file`) | unchanged URLs; now list the employee's onboarding files as `{id, kind: slotKey, label: slot title, fileName, uploadedAt}` |
| `GET /api/people` | every row gets `onboardingStatus` (`NOT_REQUIRED` when the employee has no wizard row) |
| `POST /api/people` | body accepts `isOnboardingRequired` (boolean, default true): false creates the employee as `NOT_REQUIRED` |
| `GET /api/shell` and `GET /api/me` | `data.onboarding = { status, isRequired }`; `isRequired` is true for NOT_STARTED / IN_PROGRESS / REOPENED: redirect to the wizard |

Responses use the repo's wrapper: `{ ok, message?, data }`; everything in this file is the `data` part. `POST .../submission` answers 201 with the payload (status SUBMITTED).
`422` field errors: `{ "type", "title", "status", "detail", "code":"VALIDATION_FAILED", "errors": { "<jsonPath>": "message" } }`; paths are those of the step body
(`personal.bloodGroup`, `permanentAddress.pin`, `education[1].institute`, `employments[0].toDate`, `bank.ifsc`, `statutory.pan`, `familyMembers[0].guardian.name`,
`nominations.epf[0].percentage`); for step 6 and the submission, a missing mandatory file is `attachments.<slotKey>`. Submission reports every step at once.
Present address with `isSameAsPermanent: true`: the server copies every permanent value (district included); the other present fields may be omitted.

Masking: `GET` returns bank account number, PAN, Aadhaar, UAN masked (`"XXXXXXXX1234"`) with `"hasX": true`; the full value is returned only by
`GET /api/onboarding/mine/secrets` (`{ bankAccountNumber, pan, aadhaar }`, owner only, audited) used by the Summary eye toggle and by the form when editing.
When the employee saves step 3 without changing a masked field the client sends `null` for it = keep the stored value.

## Payload (GET /api/onboarding/mine)
```
{
 "status": "IN_PROGRESS", "currentStep": 2, "stepStatus": {"1":"COMPLETE","2":"UNVISITED",...}, "isReadOnly": false, "submittedAt": null,
 "header": { "name": "AMIT JASPAL", "employeeCode": "JC074", "photoUrl": null|"/api/...", "department": null, "designation": null, "location": "Delhi",
             "pointOfContact": { "name": "...", "email": "...", "phone": "..." } | null },
 "personal": {
   "bloodGroup": use the display strings "A +ve","A -ve","B +ve","B -ve","AB +ve","AB -ve","O +ve","O -ve",
   "personalEmail","fatherName","maritalStatus":"Single|Married|Divorced|Widowed","marriageDate","spouseName","countryOfOrigin","nationality":"Indian|Others",
   "isInternationalEmployee":true|false|null,"isPhysicallyChallenged":true|false|null,"disabilityType","gender":"Male|Female|Others","dateOfBirth",
   "heightCm","weightKg","identificationMark","religion","caste","hobby" },
 "permanentAddress": { "line1","line2","line3","city","district","state","pin","country","phone" },
 "presentAddress":   { same + "isSameAsPermanent": true|false },
 "emergencyContact": { "name","mobile","relationship" },
 "education": [ { "id","qualification","fromYear","toYear","institute","gradeOrPercentage" } ],
 "courses":   [ { "id","courseName","institute","fromYear","toYear" } ],
 "employments": [ { "id","companyName","designation","lastDrawnSalary","fromDate","toDate","reasonForLeaving","companyAddress" } ],
 "bank": { "country","bankAccountNumber"(masked),"hasBankAccountNumber","ifsc","bankName","branch","branchAddress","nameAsPerBank","accountType":"Saving|Current|Salary" },
 "statutory": { "previousPfNumber","uan"(masked),"pan"(masked),"namePerPan","aadhaar"(masked),"namePerAadhaar","passportNumber","namePerPassport","passportExpiry" },
 "familyMembers": [ { "id","name","relationship","dateOfBirth","gender","bloodGroup","nationality","isMinor","hasMentalIllness",
                      "guardian": { "name","relationship","line1","line2","line3","city","state","country","pin","mobile","email" } | null } ],
 "nominations": { "epf":[{"familyMemberId","percentage"}], "eps":{"familyMemberId"}|null, "esi":{"familyMemberId"}|null, "gratuity":[{"familyMemberId","percentage"}] },
 "attachmentSlots": [ { "slotKey":"address_permanent|address_present|qualification:<educationId>|employment:<employmentId>|bank|pan|aadhaar|passport",
                        "group":"Personal Information|Previous Employment|Accounts & Statutory", "title":"Address : Permanent Address", "tooltip":"...", "isMandatory":true,
                        "files":[ {"id","fileName","mime","sizeBytes","uploadedAt"} ] } ]
}
```
Step save bodies (`PUT .../steps/{n}`): n=1 `{personal, permanentAddress, presentAddress, emergencyContact, education, courses}`; n=2 `{employments}`;
n=3 `{bank, statutory}`; n=4 `{familyMembers}`; n=5 `{nominations}`; n=6 `{}` (only checks mandatory slots, marks COMPLETE).
Saving step 1/2 changes the slots (new education/employment ids): the response carries the regenerated `attachmentSlots`. Removing an education/employment removes its files.
Saving step 4 removes nominations that point at a deleted member.

## Validation (server mirrors client; messages are the English text, frontend translates)
See spec section 10.1. Required fields per spec "Req." column. Extra: Married => marriageDate required; Physically Challenged = Yes => disabilityType required;
toYear >= fromYear; toDate >= fromDate; employment block used (any field filled) => companyName, fromDate, toDate required; DOB not in the future;
email format; phone/mobile 10 digits; pin 6 digits; IFSC `^[A-Z]{4}0[A-Z0-9]{6}$`; confirm bank number is a CLIENT-ONLY check ("Account numbers do not match", server gets one number);
account number 9 to 18 digits; PAN `^[A-Z]{5}[0-9]{4}[A-Z]$`; Aadhaar 12 digits; UAN 12 digits; passport number => passportExpiry required;
minor => guardian name, relationship, mobile required; nominations: single nominee => 100 ("Nomination % must be 100 in case of single nominee."), several => total 100,
no duplicate member in one block, EPS and ESI single; at least one education.

---
# v2 (owner's flow change, 2026-10-27): HR approval, locked portal, HR-on-behalf onboarding
Supersedes anything above that conflicts (REOPENED is gone).

## Statuses
`NOT_REQUIRED` | `NOT_STARTED` | `IN_PROGRESS` | `SUBMITTED` (waiting for HR) | `CHANGES_REQUESTED` (HR rejected, employee must fix) | `APPROVED`.
Employee is LOCKED (gate `isRequired = true`) in NOT_STARTED, IN_PROGRESS, SUBMITTED, CHANGES_REQUESTED. Only NOT_REQUIRED and APPROVED are free.

## Hard lock (server side, not only UI)
A filter (like PolicyGateFilter/SubscriptionGateFilter) answers 403 problem `onboarding-pending` for EVERY `/api/**` call of a locked employee except: auth/session/logout/refresh, `/api/me`, `/api/shell` (reduced), `/api/onboarding/**` (own wizard, bank lookups, attachments, secrets, submission), language/locale preference, own notifications read. Frontend: a locked employee sees NO menu at all (no sidebar items, no search, no bell): only a minimal bar with logo, name, language, Sign out. Wizard when NOT_STARTED/IN_PROGRESS/CHANGES_REQUESTED; a read-only "Submitted, waiting for HR review" page with the Summary when SUBMITTED; after APPROVED the normal portal.

## Employee submit / HR decision
- `POST /api/onboarding/mine/submission` -> SUBMITTED (also from CHANGES_REQUESTED = resubmit; clears the review flags). Notifies HR (in-app notification to everyone with `people.add`).
- `GET /api/onboarding/mine` additionally returns `reviewComment: string|null`, `reviewFlags: [{section, comment}]`, `reviewedAt`, `approvedAt`. Sections keys: `PERSONAL`, `ADDRESS`, `QUALIFICATIONS`, `EMPLOYMENT`, `ACCOUNTS`, `FAMILY`, `NOMINATIONS`, `ATTACHMENTS` (steps: PERSONAL/ADDRESS/QUALIFICATIONS = 1, EMPLOYMENT = 2, ACCOUNTS = 3, FAMILY = 4, NOMINATIONS = 5, ATTACHMENTS = 6). UI: red stepper step + red section header + HR comment under it for every flagged section, and a banner with the overall comment.
- HR (permission `people.add`):
  - `POST /api/people/{id}/onboarding/approvals` (body `{}`) -> APPROVED, only from SUBMITTED; employee gets in-app notification + email "Onboarding approved".
  - `POST /api/people/{id}/onboarding/rejections` body `{ comment, flags: [{section, comment}] }` (>= 1 flag, comment required) -> CHANGES_REQUESTED; employee gets in-app notification + email (the alert) naming the sections.
  - Both audited. `PUT /api/people/{id}/onboarding/status` (REOPENED) is removed.

## HR creates onboardings and fills them on behalf (new menu item "Onboard employee"; the old "Add employee" stays as is and is NOT_REQUIRED)
- `GET /api/onboardings?status=&search=&page=` (HR): list of onboardings: `{ employeeId, name, employeeCode, email, designation, department, status, submittedAt, currentStep }`.
- `POST /api/onboardings` (HR): body = the minimal master data (same fields/validation as the add-employee form's first part: firstName, lastName, workEmail, phone, dateOfJoining, designation, departmentId, managerId, roleId, location/time zone as the add-employee form needs) -> creates the employee with portal access ON, onboarding NOT_STARTED, sends the invitation email (greytHR-style "Hi NAME, Glad to have you onboard! ... Let's Get Started" with the portal link and the work email as username; MailService/MailTemplates), 201 with `{ employeeId }`.
- HR acts on the employee's behalf with the same payloads as `/mine`: `GET /api/people/{id}/onboarding` (masked), `PUT /api/people/{id}/onboarding/steps/{step}`, `PUT /api/people/{id}/onboarding/current-step`, `POST /api/people/{id}/onboarding/attachments`, `DELETE /api/people/{id}/onboarding/attachments/{fileId}`, `GET /api/people/{id}/onboarding/attachments/{fileId}/file`, `GET /api/people/{id}/onboarding/secrets` (audited), `POST /api/people/{id}/onboarding/submission` (HR submits for them = SUBMITTED, then HR may approve). Allowed in every status except APPROVED (409) and NOT_REQUIRED (404). Every write audited.
- Add-employee (`POST /api/people`): `isOnboardingRequired` default becomes FALSE and the checkbox disappears from that form (existing flow unchanged: immediate access, NOT_REQUIRED).
- Shapes as built: `GET /api/onboardings` answers `data = { rows: [{ employeeId, name, employeeCode, email, designation, department, status, submittedAt, currentStep }], total, page, take }` (query `status`, `search` on name/email/code, `page`, `pageSize` default 25 max 100; newest first; NOT_REQUIRED never listed; unknown status = 400). `POST /api/onboardings` body = `{ firstName, middleName?, lastName, workEmail, phoneCountry? ("+91"), phone, location? ("IN_IST" default), joiningOffice?, designation, departmentId, managerId, dateOfJoining, shift?, roleId }`, answers 201 `data = { employeeId }`; the father's name is NOT asked (the employee gives it in step 1). Duplicate email = the add-employee error (422 "... already uses that email."). The invitation mail is queued after the creation is committed; a failing mail is only logged.
- `GET /api/people/{id}/onboarding/secrets` answers `{ bankAccountNumber, pan, aadhaar }` (`Cache-Control: no-store`, audited). HR writes are audited with the HR as actor and `onBehalfOfEmployeeId` in the audit data. A bad slot key = 422, as for the employee.

