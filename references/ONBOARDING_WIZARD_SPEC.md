# Employee onboarding wizard: functional spec (text copy of the owner's "Juris_One_Onboarding_Spec.pdf", greytHR recording)

Use Juris One's own look (existing components/theme), NOT greytHR's. Every form starts BLANK except read-only employee master data and values already in the employee master (gender, DOB).
Sign-in is the existing email + OTP; no password screens; invitation email is out of scope. Display date format `DD Mon YYYY` (04 Mar 2019). Mandatory = red `*`. Dropdown empty value "Select".
Field errors: red border + red message under the field (e.g. "DOB is mandatory"). A section whose fields are missing/invalid shows "Some information is missing or invalid" with a warning icon on the right of its header.
Repeatable blocks: title ("Education 1", "Employment 2", "Member 1", "Nominee 1"), an x to remove at the right, and a link "+ Add another ..." below the last block.
Year fields = dropdowns, descending, current year + 1 down to 1950 (placeholders "From year" / "To year").

## Flow
HR creates employee -> (invite email later) -> employee logs in with email + OTP -> while onboarding is required and not SUBMITTED they land on the wizard (resume at last step) -> 6 steps, each "Save & Next" persists -> Information Summary -> Submit + confirm -> Success.

## Common layout (every step and the Summary)
- Heading "Hi, <EMPLOYEE NAME>" (caps), sub-text "Help us know you better! Provide all your employment information."
- Employee card: photo avatar with edit (pencil) badge (employee can upload/change profile photo: reuse the existing photo upload), Name, "Emp ID: JC074", "Point of contact: <name> (i)" (the (i) shows contact details on hover), Department, Location, Designation ("-" when empty).
- Stepper, six steps with icon + label: Personal Information, Previous Employment, Accounts & Statutory, Family Details, Nomination Details, Attachments. Active = primary colour; not visited = grey outline; completed = completed style; visited-but-incomplete/invalid = warning (orange). Steps are clickable (jumping never discards saved data).
- Footer: "Back" (outlined; disabled on step 1) and "Save & Next" (primary), centred. Save & Next validates the step, saves it (full-page overlay with a cloud icon and "Saving..."), then opens the next step at the top. Back goes to the previous step without losing entered data.
- When mandatory data is missing on the Attachments step, Save & Next is disabled and "! Complete mandatory information" appears next to it. Last step's Save & Next opens the Information Summary.

## Step 1: Personal Information (accordion with 3 numbered sections; saved once for the whole step)
### 1. Personal Information (3-column grid; labels exact)
1 Blood Group* dropdown: A +ve, A -ve, B +ve, B -ve, AB +ve, AB -ve, O +ve, O -ve
2 Personal Email* email
3 Father name* text
4 Marital Status* dropdown: Single, Married, Divorced, Widowed
5 Marriage Date: date, DISABLED unless Marital Status = Married; then enabled and mandatory
6 Spouse Name: text, disabled unless Married
7 Country Of Origin* dropdown (country list, e.g. India)
8 Nationality* dropdown: Indian, Others
9 International Employee* dropdown: Yes, No
10 Physically Challenged* dropdown: Yes, No
11 Disability Type: dropdown, disabled unless Physically Challenged = Yes (then mandatory): Locomotor, Visual, Hearing, Speech, Intellectual, Multiple, Other
12 Gender: Male, Female, Others (pre-filled from master)
13 Date of Birth: date (pre-filled from master, clearable, not in the future)
14 Height (cm) number   15 Weight (kg) number   16 Identification Mark text
17 Religion dropdown: Hindu, Muslim, Christian, Sikh, Buddhist, Jain, Parsi, Other, Prefer not to say
18 Caste text   19 Hobby text
### 2. Address
Permanent Address (9): Address* (line 1; label "Address"), "Address Line 2" and "Address Line 3" (OPTIONAL, owner decision), City*, District (optional), State* (Indian states and UTs dropdown, depends on Country), Pin* (6 digits), Country* (default India), Phone Number* (10 digits).
Present Address: checkbox "Same as above" + the same 9 fields. Ticked = copy EVERY permanent value including District, keep in sync, fields read-only; unticked = editable.
Emergency Contact/Address: Name*, Mobile Number* (10 digits), Relationship* dropdown (exact list): Brother, Daughter, Father, Husband, Mother, Sister, Son, Wife.
### 3. Qualifications
Education blocks "Education 1.." (at least one; x removes, keep at least one; link "Add another education"): Qualification* dropdown (B.E, B.Tech, BCA, M.Tech, MBA, MCA, plus 10th, 12th, Diploma, B.A, B.Com, B.Sc, LLB, LLM, M.A, M.Com, M.Sc, CA, CS, PhD, Other), From Year* (placeholder "From year"), To Year* ("To year", >= From Year), Institute* (e.g. "MDU, Rohtak"), Grade/Percentage* (placeholder "CGPA/GPA/Percentage").
Course Details blocks "Course 1.." (optional; link "Add another course"): Course Name (placeholder "Enter Name"), Institute (placeholder "Name of Institution"), From Year, To Year.

## Step 2: Previous Employment (heading "Previous Employment")
Blocks "Employment 1.." (x removes; link "Add another Employment"). No field is starred (freshers can skip); if a block is partly filled require Company Name, From Date and To Date (To >= From). Fields: Company Name, Designation, Last Drawn Salary (rupees; placeholder "Enter Last Drawn Salary"), From Date, To Date, Reason for Leaving (textarea, placeholder "Enter Reason"), Company Address (full-width textarea). Each employment creates an upload slot in Step 6.

## Step 3: Accounts & Statutory (heading "Identifications")
Bank Account Details: Country* (Select / India); Bank Account Number* (masked as dots, digits only 9-18); Confirm Bank Account Number* (placeholder "Enter Account Number"; must equal; else red border + "Account numbers do not match"); Bank Code* (IFSC, 11 chars e.g. ICIC0000571; NO "Verify" link and NO bank search: removed by the owner); Name as per Bank Account*; Account Type* dropdown: Saving, Current, Salary.
PF Account Details: Previous PF Account Number (placeholder "HR/FBD/0003256/000/0000125"), UAN (12 digits). Both optional.
Permanent Account Number (PAN): PAN Number* (AAAAA9999A, auto-uppercase), Name in PAN*.
Aadhaar: Aadhaar Number* (12 digits), Name in Aadhaar*.
Passport: Passport Number, Name in Passport, Expiry Date (required if a passport number is entered). All optional otherwise.

## Step 4: Family Details (heading "Family Details")
Blocks "Member 1.." (x removes; link "Add another member"): Name*, Relationship* (Father, Mother, Husband, Wife, Son, Daughter, Brother, Sister), DOB* (error "DOB is mandatory"), Gender* (Male, Female, Others), Blood Group* (same list as personal), Nationality* (Indian, Others), checkbox Minor (may auto-tick when age < 18), checkbox Mental Illness.
When Minor is ticked show the Guardian block: Guardian Name*, Relationship*, Address Line 1, 2, 3, City, State, Country, Pin, Mobile*, Email.

## Step 5: Nomination Details (heading "Nominations" with an (i) tooltip: "You can nominate more than one family member and add percentage of share for your EPF, EPS, ESI, Gratuity accounts.")
EPF Nomination: repeatable "Nominee 1.." = Name (dropdown) + Nomination % (placeholder "Eg. 50"), link "Add another member", x remove, total must be 100.
EPS Nomination: single "Nominee", Name dropdown, no %.   ESI Nomination: single "Nominee", Name dropdown, no %.
Gratuity Nomination: repeatable like EPF.
Name dropdown lists the Step 4 family members as "Name (Relationship)" (e.g. "Anju Devi (Mother)") and follows changes in Step 4. Single nominee must be 100 ("Nomination % must be 100 in case of single nominee."); several must sum to exactly 100; a member cannot be picked twice in one block. With no family members show a hint linking back to Family Details.

## Step 6: Attachments
Slots are generated from the data of steps 1 to 3, grouped in collapsible sections, each slot with a heading, an (i) tooltip, file cards and an "Add Attachment" tile; hint under each slot "Only pdf, doc, docx, jpg & png. Max 10MB".
1. Personal Information: "Address : Permanent Address" * (slot address_permanent); "Address : Present Address" * (address_present) (tooltip for both: "For proof attach one of the following aadhaar card/ driving license/ passport/ electricity bill, water bill/ bank pass book."); "Qualification: <qualification>" * per Education entry (qualification:<educationId>) (tooltip: "For proof attach certificate (mandatory) and marksheet (mandatory).")
2. Previous Employment: "Previous Employment: <Company Name>" per employment (employment:<employmentId>), NOT mandatory, no star (tooltip: "For proof attach latest pay slip (mandatory) and one of the following offer letter/appointment letter/ company relieving/ experience letter/ form 16.")
3. Accounts & Statutory: "Bank Account Details" * (bank) (tooltip: "For proof attach Passbook/cancelled Check"); "Permanent Account Number (PAN)" * (pan); "Aadhaar" * (aadhaar); "Passport" (passport) only when a passport number was entered (conditional mandatory).
Multiple files per slot; after each upload a new "Add Attachment" tile appears (3 per row, then wraps). File card: type icon (PDF/JPG...), file name, size ("153 kb"), progress bar while uploading, then view (eye) and remove (x). Allowed pdf, doc, docx, jpg, png; max 10 MB each. Files upload immediately. Until every mandatory slot has >= 1 file: Save & Next disabled + "! Complete mandatory information".

## Information Summary (route .../summary)
Same header, title "Information Summary". Left sticky anchor nav: Personal Information, Previous Employment, Accounts & Statutory, Family Details, Nomination Details. Cards: 1. Personal Information, 2. Address (permanent + present with attachments), 3. Qualifications (education + courses with attachments), 4. Previous Employment (with attachments), 5. Accounts & Statutory (bank, PF, PAN, Aadhaar, Passport, with attachments), 6. Family Details (every field incl. guardian fields for each member), 7. Nomination Details (EPF Name + %, EPS Name, ESI Name, Gratuity Name + %). Each card has an edit icon that opens the matching step. Read-only label/value pairs in 3 columns, "-" (or an em dash) for empty, dates `10 Aug 1996`. Attachments inside their card with view and download. Aadhaar ("7***********") and bank account number are masked with an eye toggle. Buttons "Edit Form" (outlined, wizard at step 1) and "Submit Onboarding" (primary).
Submit modal titled "Submit": "Are you sure you want to submit your onboarding information ?" Yes / No / close. Yes shows "Loading...", sets SUBMITTED + submitted_at, then Success page: "Well Done <FIRST NAME>", congratulations text, button "Go to dashboard". After submission the form is read-only for the employee unless HR reopens it.

## Do not repeat greytHR glitches
"Adresss" typo; the confirm-account message ("Account numbers do not match" instead); District must be copied by "Same as above".

## HR side (minimal)
Employee list/profile shows onboarding status (Not started / In progress / Submitted) and a read-only view of the submitted data and documents (reuse the Summary component). HR can reopen a submitted onboarding. Anything bigger: ask the owner.

## Storage (dev and every env): files go under `<jurisone.storage-dir>/onboarding-forms/<YYYY-MM>/<employeeCode>/<slotFolder>/<uuid>-<safeFileName>` where YYYY-MM is the upload month (so files are easy to find per month), slotFolder is the slot key made path-safe. Never trust the client file name.
