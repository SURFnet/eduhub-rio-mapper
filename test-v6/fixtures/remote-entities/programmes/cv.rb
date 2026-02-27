require 'json'

j = ARGV[0].then { File.read it }.then { JSON.parse it }

raise ARGV[0] if j.is_a?(Array)

if cs = j['consumers']
  j.delete('consumers')
  c = cs.detect { it["consumerKey"] == 'rio' }
  c.delete('consentParticipationSTAP')
  j['consumer'] = c
end

if j['programType']
  j['programmeType'] = j.delete('programType')
  if j['programmeType'] == 'program'
    j['programmeType'] = 'programme'
  end
end

j['consumer'] ||= { "consumerKey": "rio" }
c = j['consumer']

if es = j['educationSpecification']
  j.delete('educationSpecification')
  c['specificationId'] = es
end

if spectype = j['educationSpecificationType']
  j.delete('educationSpecificationType')
  if spectype == 'program'
    spectype = 'programme'
  end
  c['specificationType'] = spectype
  j['programmeType'] = 'specification'
  if c['educationSpecificationSubType'] == 'variant'
    c.delete('educationSpecificationSubType')
    c['variantOf'] = j['parent']
  end
end

if j['programId']
  j['programmeId'] = j.delete('programId')
end

if j['programmeType'] == 'specification'
  unless c['specificationType']
    if c['type']
      c['specificationType'] = c.delete('type')
    else
      STDERR.puts "Missing consumer.specificationType for #{ARGV[0]}"
      exit
    end
  end
elsif j['programmeType'].nil?
  STDERR.puts "Missing programmeType for #{ARGV[0]}"
  exit
end

# ensure programmes with programmeType specification have consumer.specificationType, formerly educationSpecificationType
# what to do with educationSpecificationSubtype? we have consumer.variantOf now

puts j.to_json
