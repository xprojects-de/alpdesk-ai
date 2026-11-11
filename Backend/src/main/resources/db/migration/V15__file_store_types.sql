-- More types of file store (docs/architecture/file-stores.md, roadmap point 44): S3 - a bucket of Amazon S3 or of a
-- compatible store, read with an access key - and SHAREPOINT - a document library of a SharePoint site or a OneDrive,
-- read by an app registration of the customer's Entra ID. Their settings are JSON in file_store.settings like the other
-- types', the secret (access key's or app's) is file_store.secret.
ALTER TABLE file_store DROP CONSTRAINT file_store_type_check;
ALTER TABLE file_store ADD CONSTRAINT file_store_type_check CHECK (type IN ('SFTP', 'FTPS', 'S3', 'SHAREPOINT'));
